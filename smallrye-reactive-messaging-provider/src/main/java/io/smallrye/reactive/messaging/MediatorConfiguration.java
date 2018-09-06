package io.smallrye.reactive.messaging;

import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.eclipse.microprofile.reactive.streams.ProcessorBuilder;
import org.eclipse.microprofile.reactive.streams.PublisherBuilder;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public class MediatorConfiguration {

  private final Method method;

  private Shape shape;

  private final Class<?> beanClass;

  private Incoming incoming;

  private Outgoing outgoing;

  /**
   * What does the mediator products and how is it produced
   */
  private Production production = Production.NONE;
  /**
   * What does the mediator consumes and how is it produced
   */
  private Consumption consumption = Consumption.NONE;

  /**
   * Use MicroProfile Stream Stream Ops Type.
   */
  private boolean useBuilderTypes = false;

  public enum Production {
    STREAM_OF_MESSAGE,
    STREAM_OF_PAYLOAD,

    INDIVIDUAL_PAYLOAD,
    INDIVIDUAL_MESSAGE,
    COMPLETION_STAGE_OF_PAYLOAD,
    COMPLETION_STAGE_OF_MESSAGE,

    NONE
  }

  public enum Consumption {
    STREAM_OF_MESSAGE,
    STREAM_OF_PAYLOAD,

    MESSAGE,
    PAYLOAD,

    NONE
  }

  public MediatorConfiguration(Method method, Class<?> beanClass) {
    this.method = Objects.requireNonNull(method, "'method' must be set");
    this.beanClass =  Objects.requireNonNull(beanClass, "'beanClass' must be set");
  }

  public Shape shape() {
    return shape;
  }

  public void compute(Incoming incoming, Outgoing outgoing) {
    if (incoming != null  && outgoing != null) {
      // it can be either a processor or a stream transformer
      if (isReturningAPublisherOrAPublisherBuilder()  && isConsumingAPublisherOrAPublisherBuilder()) {
        shape = Shape.STREAM_TRANSFORMER;
      } else {
        shape = Shape.PROCESSOR;
      }
    } else if (incoming != null) {
      shape = Shape.SUBSCRIBER;
    } else {
      shape = Shape.PUBLISHER;
    }

    switch (shape) {
      case SUBSCRIBER: validateSubscriber(incoming); break;
      case PUBLISHER: validatePublisher(outgoing); break;
      case PROCESSOR: validateProcessor(incoming, outgoing); break;
      case STREAM_TRANSFORMER: validateStreamTransformer(incoming, outgoing); break;
      default: throw new IllegalStateException("Unknown shape: " + shape);
    }
  }

  private void validateStreamTransformer(Incoming incoming, Outgoing outgoing) {
    this.incoming = incoming;
    this.outgoing = outgoing;
    // 1.  Publisher<Message<O>> method(Publisher<Message<I>> publisher)
    // 2. Publisher<O> method(Publisher<I> publisher)
    // 3. PublisherBuilder<Message<O>> method(PublisherBuilder<Message<I>> publisher)
    // 4. PublisherBuilder<O> method(PublisherBuilder<I> publisher)
    validateMethodConsumingAndProducingAPublisher();
  }


  private void validateProcessor(Incoming incoming, Outgoing outgoing) {
    this.incoming = incoming;
    this.outgoing = outgoing;

    // Supported signatures:
    // 1.  Processor<Message<I>, Message<O>> method()
    // 2.  Processor<I, O> method()
    // 3.  ProcessorBuilder<Message<I>, Message<O>> method()
    // 4.  ProcessorBuilder<I, O> method()

    // 5.  Publisher<Message<O>> method(Message<I> msg)
    // 6.  Publisher<O> method(I payload)
    // 7.  PublisherBuilder<Message<O>> method(Message<I> msg)
    // 8.  PublisherBuilder<O> method(I payload)

    // 9. Message<O> method(Message<I> msg)
    // 10. O method(I payload)
    // 11. CompletionStage<O> method(I payload)
    // 12. CompletionStage<Message<O>> method(Message<I> msg)

    Class<?> returnType = method.getReturnType();
    if (ClassUtils.isAssignable(returnType, Processor.class)  || ClassUtils.isAssignable(returnType, ProcessorBuilder.class)) {
      // Case 1, 2 or 3, 4
      validateMethodReturningAProcessor();
    } else if (
      ClassUtils.isAssignable(returnType, Publisher.class) || ClassUtils.isAssignable(returnType, PublisherBuilder.class)) {
      // Case 5, 6, 7, 8
      if (method.getParameterCount() != 1) {
        throw new IllegalArgumentException("Invalid method annotated with @Outgoing and @Incoming " + methodAsString()
          + " - one parameter expected");
      }
      validateMethodConsumingSingleAndProducingAPublisher();
    } else {
      // Case 13, 14, 15, 16
      Class<?> param = method.getParameterTypes()[0];
      if (ClassUtils.isAssignable(returnType, CompletionStage.class)) {
        // Case 15 or 16
        Type type = getParameterFromReturnType(method, 0)
          .orElseThrow(() -> getIncomingAndOutgoingError("Expected a type parameter in the return CompletionStage"));
        production = TypeUtils.isAssignable(type, Message.class) ? Production.COMPLETION_STAGE_OF_MESSAGE : Production.COMPLETION_STAGE_OF_PAYLOAD;
        consumption = ClassUtils.isAssignable(param, Message.class) ? Consumption.MESSAGE : Consumption.PAYLOAD;
      } else {
        // Case 13 or 14
        production = ClassUtils.isAssignable(returnType, Message.class) ? Production.INDIVIDUAL_MESSAGE : Production.INDIVIDUAL_PAYLOAD;
        consumption = ClassUtils.isAssignable(param, Message.class) ? Consumption.MESSAGE : Consumption.PAYLOAD;
      }
    }
  }

  private void validateMethodConsumingAndProducingAPublisher() {
    // The mediator produces and consumes a stream
    Type type = getParameterFromReturnType(method, 0)
      .orElseThrow(() -> getOutgoingError("Expected a type parameter for the returned Publisher"));
    production = TypeUtils.isAssignable(type, Message.class) ? Production.STREAM_OF_MESSAGE : Production.STREAM_OF_PAYLOAD;

    Type pType = getParameterFromMethodArgument(method, 0, 0)
      .orElseThrow(() -> getIncomingError("Expected a type parameter for the consumed Publisher"));
    consumption = TypeUtils.isAssignable(pType, Message.class) ? Consumption.STREAM_OF_MESSAGE : Consumption.STREAM_OF_PAYLOAD;

    useBuilderTypes = ClassUtils.isAssignable(method.getReturnType(), PublisherBuilder.class);
    // TODO Ensure that the parameter is also a publisher builder.
  }

  private void validateMethodConsumingSingleAndProducingAPublisher() {
    Type type = getParameterFromReturnType(method, 0)
      .orElseThrow(() -> getOutgoingError("Expected a type parameter for the returned Publisher"));
    production =
      TypeUtils.isAssignable(type, Message.class) ? Production.STREAM_OF_MESSAGE : Production.STREAM_OF_PAYLOAD;

    consumption = ClassUtils.isAssignable(method.getParameterTypes()[0], Message.class) ? Consumption.STREAM_OF_MESSAGE : Consumption.STREAM_OF_PAYLOAD;

    useBuilderTypes = ClassUtils.isAssignable(method.getReturnType(), PublisherBuilder.class);
  }

  private void validateMethodReturningAProcessor() {
    if (method.getParameterCount() != 0) {
      throw getIncomingAndOutgoingError("the method must not have parameters");
    }
    Type type1 = getParameterFromReturnType(method, 0)
      .orElseThrow(() -> getIncomingAndOutgoingError("Expected 2 type parameters for the returned Processor"));
    consumption =  TypeUtils.isAssignable(type1, Message.class) ? Consumption.STREAM_OF_MESSAGE : Consumption.STREAM_OF_PAYLOAD;

    Type type2 = getParameterFromReturnType(method, 1)
      .orElseThrow(() -> getIncomingAndOutgoingError("Expected 2 type parameters for the returned Processor"));
      production = TypeUtils.isAssignable(type2, Message.class) ? Production.STREAM_OF_MESSAGE : Production.STREAM_OF_PAYLOAD;

    useBuilderTypes = ClassUtils.isAssignable(method.getReturnType(), ProcessorBuilder.class);
  }

  private Optional<Type> getParameterFromReturnType(Method method, int index) {
    Type type = method.getGenericReturnType();
    if (!(type instanceof ParameterizedType)) {
      return Optional.empty();
    }
    Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
    if (arguments.length >= index + 1) {
      return Optional.of(arguments[0]);
    }
    return Optional.empty();
  }

  private Optional<Type> getParameterFromMethodArgument(Method method, int argIndex, int index) {
    Type[] types = method.getGenericParameterTypes();
    if (types.length < argIndex) {
      return Optional.empty();
    }
    Type type = method.getGenericReturnType();
    if (!(type instanceof ParameterizedType)) {
      return Optional.empty();
    }
    Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
    if (arguments.length >= index + 1) {
      return Optional.of(arguments[0]);
    }
    return Optional.empty();
  }

  private void validatePublisher(Outgoing outgoing) {
    this.outgoing = outgoing;

    // Supported signatures:
    // 1. Publisher<Message<O>> method()
    // 2. Publisher<O> method()
    // 3. PublisherBuilder<Message<O>> method()
    // 4. PublisherBuilder<O> method()
    // 5. O method() O cannot be Void
    // 6. Message<O> method()
    // 7. CompletionStage<Message<O>> method()
    // 8. CompletionStage<O> method()

    Class<?> returnType = method.getReturnType();
    Type type = method.getGenericReturnType();
    if (type instanceof ParameterizedType) {
      // Expect only 1 type
      type = ((ParameterizedType) type).getActualTypeArguments()[0];
    }

    if (returnType == Void.TYPE) {
      throw new IllegalArgumentException("Invalid method annotated with @Outgoing: " + methodAsString()
        + " - the method must not be `void`");
    }

    if (method.getParameterCount() != 0) {
      throw new IllegalArgumentException("Invalid method annotated with @Outgoing: " + methodAsString()
        + " - no parameters expected");
    }

    consumption = Consumption.NONE;

    if (ClassUtils.isAssignable(returnType, Publisher.class)) {
      // Case 1 or 2
      production = TypeUtils.isAssignable(Message.class, type) ? Production.STREAM_OF_MESSAGE : Production.STREAM_OF_PAYLOAD;
      return;
    }

    if (ClassUtils.isAssignable(returnType, PublisherBuilder.class)) {
      // Case 3 or 4
      production = TypeUtils.isAssignable(Message.class, type) ? Production.STREAM_OF_MESSAGE : Production.STREAM_OF_PAYLOAD;
      useBuilderTypes = true;
      return;
    }

    if (ClassUtils.isAssignable(returnType, Message.class)) {
      // Case 5
      production = Production.INDIVIDUAL_MESSAGE;
      return;
    }

    if (ClassUtils.isAssignable(returnType, CompletionStage.class)) {
      // Case 7 and 8
      Type t = getParameterFromReturnType(method, 0)
        .orElseThrow(() -> getOutgoingError("expected a parameter for the returned CompletionStage"));
      production = TypeUtils.isAssignable(t, Message.class) ? Production.COMPLETION_STAGE_OF_MESSAGE : Production.COMPLETION_STAGE_OF_PAYLOAD;
      return;
    }

     // Case 6
    production = Production.INDIVIDUAL_PAYLOAD;
  }

  private IllegalArgumentException getOutgoingError(String message) {
    return new IllegalArgumentException("Invalid method annotated with @Outgoing: " + methodAsString() + " - " + message);
  }

  private IllegalArgumentException getIncomingError(String message) {
    return new IllegalArgumentException("Invalid method annotated with @Incoming: " + methodAsString() + " - " + message);
  }

  private IllegalArgumentException getIncomingAndOutgoingError(String message) {
    return new IllegalArgumentException("Invalid method annotated with @Incoming and @Outgoing: " + methodAsString() + " - " + message);
  }

  private void validateSubscriber(Incoming incoming) {
    this.incoming = incoming;
    this.production = Production.NONE;

    // Supported signatures:
    // 1. Subscriber<Message<I>> method()
    // 2. Subscriber<I> method()
    // 3. CompletionStage<?> method(Message<I> m)
    // 4. CompletionStage<?> method(I i)
    // 5. void/? method(Message<I> m)
    // 6. void/? method(I i)

    Class<?> returnType = method.getReturnType();
    Optional<Type> type = getParameterFromReturnType(method, 0);

    if (ClassUtils.isAssignable(returnType, Subscriber.class)) {
      // Case 1 or 2.
      // Validation -> No parameter
      if (method.getParameterCount() != 0) {
        // TODO Revisit it with injected parameters
        throw getIncomingError("when returning a Subscriber, no parameters are expected");
      }
      Type p = type.orElseThrow(() -> getIncomingError("the returned Subscriber must declare a type parameter"));
      // Need to distinguish 1 or 2
      consumption = TypeUtils.isAssignable(p, Message.class) ? Consumption.STREAM_OF_MESSAGE : Consumption.STREAM_OF_PAYLOAD;
      return;
    }

    if (ClassUtils.isAssignable(returnType, CompletionStage.class)) {
      // Case 3 or 4
      // Expected parameter 1, Message or payload
      if (method.getParameterCount() != 1) {
        // TODO Revisit it with injected parameters
        throw getIncomingError("when returning a CompletionStage, one parameter is expected");
      }

      Class<?> param = method.getParameterTypes()[0];
      // Distinction between 3 and 4
      consumption = ClassUtils.isAssignable(param, Message.class) ? Consumption.MESSAGE : Consumption.PAYLOAD;
      return;
    }

    // Case 5 and 6, void | x with 1 parameter
    if (method.getParameterCount() == 1) {
      // TODO Revisit it with injected parameters
      Class<?> param = method.getParameterTypes()[0];
      // Distinction between 3 and 4
      consumption = ClassUtils.isAssignable(param, Message.class) ? Consumption.MESSAGE : Consumption.PAYLOAD;
      return;
    }

    throw getIncomingError("Unsupported signature");
  }

  public String getOutgoing() {
    if (outgoing == null) {
      return null;
    }
    return outgoing.value();
  }


  public String getIncoming() {
    if (incoming == null) {
      return null;
    }
    return incoming.value();
  }

  public String getIncomingProviderType() {
    if (incoming == null) {
      return null;
    }
    return incoming.provider().getName();
  }

  public String getOutgoingProviderType() {
    if (outgoing == null) {
      return null;
    }
    return outgoing.provider().getName();
  }

  public String methodAsString() {
    return beanClass.getName() + "#" + method.getName();
  }

  public Method getMethod() {
    return method;
  }

  public Class<?> getBeanClass() {
    return beanClass;
  }

  public Consumption consumption() {
    return consumption;
  }

  public Production production() {
    return production;
  }

  public boolean usesBuilderTypes() {
    return useBuilderTypes;
  }

  private boolean isReturningAPublisherOrAPublisherBuilder() {
    Class<?> returnType = method.getReturnType();
    return ClassUtils.isAssignable(returnType, Publisher.class) || ClassUtils.isAssignable(returnType, PublisherBuilder.class);
  }


  private boolean isConsumingAPublisherOrAPublisherBuilder() {
    Class<?>[] types = method.getParameterTypes();
    if (types.length >= 1) {
      Class<?> type = types[0];
      return ClassUtils.isAssignable(type, Publisher.class) || ClassUtils.isAssignable(type, PublisherBuilder.class);
    }
    return false;
  }
}
