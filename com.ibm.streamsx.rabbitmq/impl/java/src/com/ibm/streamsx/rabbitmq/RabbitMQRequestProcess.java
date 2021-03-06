/* Generated by Streams Studio: September 1, 2017 at 1:38:54 AM EDT */
package com.ibm.streamsx.rabbitmq;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.logging.TraceLevel;
import com.ibm.streams.operator.metrics.Metric;
import com.ibm.streams.operator.model.CustomMetric;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPortSet.WindowPunctuationOutputMode;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streamsx.rabbitmq.i18n.Messages;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;


@PrimitiveOperator(name="RabbitMQRequestProcess", namespace="com.ibm.streamsx.rabbitmq", description = RabbitMQRequestProcess.DESC)
@InputPorts({@InputPortSet(description="Port that excretes requests", cardinality=1, optional=false, windowingMode=WindowMode.NonWindowed, windowPunctuationInputMode=WindowPunctuationInputMode.Oblivious), @InputPortSet(description="Optional input ports", optional=true, windowingMode=WindowMode.NonWindowed, windowPunctuationInputMode=WindowPunctuationInputMode.Oblivious)})
@OutputPorts({@OutputPortSet(description="Port that consumes responses", cardinality=1, optional=false, windowPunctuationOutputMode=WindowPunctuationOutputMode.Generating), @OutputPortSet(description="Optional output ports", optional=true, windowPunctuationOutputMode=WindowPunctuationOutputMode.Generating)})
public class RabbitMQRequestProcess extends RabbitMQSource {
	private final static Logger trace = Logger.getLogger(RabbitMQBaseOper.class.getCanonicalName());	
	class requestContext {
		requestContext(String replyTo, long deliveryTag) {
			this.replyTo = replyTo;
			this.deliveryTag = deliveryTag;
		}
		public String replyTo;
		public long deliveryTag;
	};
	ConcurrentHashMap<String, requestContext> correlationQueue = null;
	// TODO * move in from Sink should this be moved up to ...BaseOper
	Integer deliveryMode = 1;
	private Metric lostCorrelationIds;
	
	int maxMessageSendRetries = 0;
	int messageSendRetryDelay = 10000;


	/**
     * Initialize this operator. Called once before any tuples are processed.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
	@Override
	public synchronized void initialize(OperatorContext context)
			throws Exception {
    	// Must call super.initialize(context) to correctly setup an operator.
		super.initialize(context);
		trace.log(TraceLevel.INFO,"Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() ); 
		super.initSchema(getOutput(0).getStreamSchema());
		trace.log(TraceLevel.INFO, this.getClass().getName() + "Operator " + context.getName() //$NON-NLS-1$
				+ " initializing in PE: " + context.getPE().getPEId() //$NON-NLS-1$
				+ " in Job: " + context.getPE().getJobId()); //$NON-NLS-1$

		// produce tuples returns immediately, but we don't want ports to close

		correlationQueue = new ConcurrentHashMap<String, requestContext>(); 		

		createAvoidCompletionThread();

		processThread = getNewConsumerThread(correlationQueue);

		processThread.setDaemon(false);
        
        // TODO:
        // If needed, insert code to establish connections or resou<es to communicate an external system or data store.
        // The configuration information for this may come from parameters supplied to the operator invocation, 
        // or external configuration files or a combination of the two.
	}
	
	/**
	 * Submit new tuples to the output stream
	 * @throws InterruptedException 
	 * @throws IOException 
	 * @throws MalformedURLException 
	 * @throws Exception 
	 */
	private void produceTuples() throws MalformedURLException, IOException, InterruptedException, Exception{
		
		// After all the ports are ready, but before we start 
		// sending messages, setup our connection, channel, exchange and queue
		super.initializeRabbitChannelAndConnection();	
		channel.basicQos(1, false); // FairDispatch not RoundRobin

		bindAndSetupQueue();
		
		DefaultConsumer consumer = getNewDefaultConsumer();
		channel.basicConsume(queueName, false, consumer); // no autoAck !
		
		while (!Thread.interrupted()){

			isConnected.waitForMetricChange();
			if (isConnected.getValue() != 1
					&& newCredentialsExist()){
				trace.log(TraceLevel.WARN,
						"New properties have been found so the client is restarting."); //$NON-NLS-1$
				resetRabbitClient();
				consumer = getNewDefaultConsumer();
				System.out.println("in thread loop");				
				channel.basicConsume(queueName, false, consumer);
			}
		}
	}	
	
	private Thread getNewConsumerThread( ConcurrentHashMap<String, requestContext>corrolationQueue) {
		return getOperatorContext().getThreadFactory().newThread(
				new Runnable() {

					@Override
					public void run() {
						try {
							produceTuples();
						} catch (Exception e) {
							e.printStackTrace();
							trace.log(TraceLevel.ERROR, e.getMessage());
						}
					}

				});
	}	

    /**
     * Notification that initialization is complete and all input and output ports 
     * are connected and ready to receive and submit tuples.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
	
	
	// TODO * can this duplication be removed? : use the Source version, issue with the correlationID. 
    private DefaultConsumer getNewDefaultConsumer() {
		return new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope,
					AMQP.BasicProperties properties, byte[] body)
					throws IOException {
				trace.log(TraceLevel.INFO,"RabbitMQRequest@handleDelivery entry");				
				if (isConnected.getValue() == 0){
					// We know we are connected if we're sending messages
					isConnected.setValue(1);
				}
				StreamingOutput<OutputTuple> out = getOutput(0);
				OutputTuple tuple = out.newTuple();

				messageAH.setValue(tuple, body);
				
				if (routingKeyAH.isAvailable()) {
					tuple.setString(routingKeyAH.getName(),
							envelope.getRoutingKey());
					if (trace.isLoggable(TraceLevel.DEBUG))
						trace.log(TraceLevel.DEBUG, routingKeyAH.getName() + ":" //$NON-NLS-1$
								+ envelope.getRoutingKey());
				} 				
				
				if (messageHeaderAH.isAvailable()){ 			// TODO * is this right. 
					Map<String, Object> msgHeader = properties.getHeaders();
					if (msgHeader != null && !msgHeader.isEmpty()){
						Map<String, String> headers = new HashMap<String,String>();
						Iterator<Entry<String,Object>> it = msgHeader.entrySet().iterator();
						while (it.hasNext()){
							Map.Entry<String, Object> pair = it.next();
							if (trace.isLoggable(TraceLevel.DEBUG))
								trace.log(TraceLevel.DEBUG, "Header: " + pair.getKey() + ":" + pair.getValue().toString()); //$NON-NLS-1$ //$NON-NLS-2$
							headers.put(pair.getKey(), pair.getValue().toString());
						}
						tuple.setMap(messageHeaderAH.getName(), headers);
					}
				}
				String correlationId = properties.getCorrelationId(); 
				trace.log(TraceLevel.INFO,"RabbitMQRequestProcess@handleDelivery +correlationId: " + correlationId);				
				if (correlationIdAH.isAvailable()) {
					correlationId = properties.getCorrelationId(); 
					trace.log(TraceLevel.INFO,"@handleDelivery correlationId: " + correlationId, " replyTo:" + properties.getReplyTo());																			
					correlationQueue.put(correlationId, new requestContext(properties.getReplyTo(),envelope.getDeliveryTag()));
					tuple.setString(correlationIdAH.getName(), correlationId);					
				}

				// Submit tuple to output stream
				try {
					out.submit(tuple);
				} catch (Exception e) {
					trace.log(TraceLevel.ERROR, "Catching submit exception" + e.getMessage()); //$NON-NLS-1$
					e.printStackTrace();
				}
			}
			
			@Override
			public void handleCancelOk(String consumerTag) {
				trace.log(TraceLevel.INFO,"Recieved cancel signal at consumer"); //$NON-NLS-1$
				super.handleCancelOk(consumerTag);
			}
			
			@Override
			public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
				trace.log(TraceLevel.INFO,"Recieved shutdown signal at consumer"); //$NON-NLS-1$
				super.handleShutdownSignal(consumerTag, sig);
			}
		};
	}
    
    /**
     * Process an incoming tuple that arrived on the specified port.
     * <P>
     * Copy the incoming tuple to a new output tuple and submit to the output port. 
     * </P>
     * @param inputStream Port the tuple is arriving on.
     * @param tuple Object representing the incoming tuple.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    // TODO ? Moved in from Sink, should it the core be moved up to Basic. 
	@SuppressWarnings("unchecked")
	@Override
	public void process(StreamingInput<Tuple> stream, Tuple tuple) throws Exception {

		String correlationId = null;
		// Handle case of lost connection/failed authentication
		// but we have new credentials from appConfig
		trace.log(TraceLevel.INFO,"RabbitMQRequestProcess@process ");						
		if (isConnected.getValue() == 0
				&& newCredentialsExist()){
			try {
				readyForShutdown = false;
				resetRabbitClient();
			} finally {
				readyForShutdown = true;
			}
		}
		byte[] message = messageAH.getBytes(tuple);
		
		String routingKey = ""; //$NON-NLS-1$
		requestContext rc = null;
		Map<String, Object> headers = new HashMap<String, Object>();
		// Do not use the routing key, use the value sent with input message.  

		if (correlationIdAH.isAvailable()) {
			correlationId = correlationIdAH.getString(tuple);			
			trace.log(TraceLevel.INFO,"@process correlationId: " + correlationId);
			rc  = correlationQueue.getOrDefault(correlationId, null);
			
			if (rc == null) {
				trace.warning(Messages.getString("NO_ROUTINGKEY_FOR_CORRELATIONID", correlationId));						
				lostCorrelationIds.increment();
				return;
			}
			if (!correlationQueue.remove(correlationId, rc)) {
				trace.warning(String.format(Messages.getString("ROUTINGKEY_VANISHED", correlationId)));
				lostCorrelationIds.increment();				
				return;
			}
		} else {
			// missing mandatory attribute
			throw new Exception(Messages.getString("ATTRIBUTE_NOT_AVAILABLE",correlationIdAH.getName() )); //$NON-NLS-1$			

		}
		routingKey = rc.replyTo;
		BasicProperties.Builder propsBuilder = new BasicProperties.Builder();
		if (messageHeaderAH.isAvailable()) {
			headers = (Map<String, Object>) tuple.getMap(messageHeaderAH.getName());
			propsBuilder.headers(headers);
		}
		propsBuilder.deliveryMode(deliveryMode);
		propsBuilder.correlationId(correlationId);

		try {
			trace.log(TraceLevel.INFO, "corrId:" + correlationId + " acking the original request");
			channel.basicAck(rc.deliveryTag, false);
			
			if (trace.isLoggable(TraceLevel.DEBUG))
				trace.log(TraceLevel.DEBUG, "corrId:" + correlationId +  " send response:" + message.toString()); //$NON-NLS-1$ //$NON-NLS-2$
			
			channel.basicPublish(exchangeName, routingKey, propsBuilder.build(), message);  // send response. 
			if (isConnected.getValue() == 0){
				// We succeeded at publish, so we must be connected
				// Adding this to deal with an issue where we catch 
				// a stale AuthorizationException that makes us look 
				// disconnected
				isConnected.setValue(1); 
			}

		} catch (Exception e) {
			trace.log(TraceLevel.ERROR, "Exception message:" + e.getMessage() + "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
			handleFailedPublish(message, routingKey, propsBuilder);
		}
	}

	private void handleFailedPublish(byte[] message, String routingKey, BasicProperties.Builder propsBuilder) {
		Boolean failedToSend = true;
		int attemptCount = 0;
		while (failedToSend && attemptCount < maxMessageSendRetries) {
			attemptCount++;
			try {
				Thread.sleep(messageSendRetryDelay);
				trace.log(TraceLevel.ERROR, "Attempting to resend. Try number: " + attemptCount); //$NON-NLS-1$
				channel.basicPublish(exchangeName, routingKey, propsBuilder.build(), message);
				failedToSend = false;
			} catch (Exception e1) {
				e1.printStackTrace();
			}
		}

		// if we still can't send after the number of maxMessageSendRetries,
		// we want to log error and move on
		if (failedToSend) {
			trace.log(TraceLevel.ERROR, "Failed to send message after " + attemptCount + " attempts."); //$NON-NLS-1$ //$NON-NLS-2$
		}		
	}

	@CustomMetric(name = "lostCorrelationIds", kind = Metric.Kind.COUNTER,
		    description = "The number of times a corrleation key was not found.")
	public void setLostCorrelationIds(Metric lostCorrelationIds) {
		this.lostCorrelationIds = lostCorrelationIds;
	}	
	

    
  
    /**
     * Shutdown this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.

    public synchronized void shutdown() throws Exception {
        OperatorContext context = getOperatorContext();
		trace.log(TraceLevel.INFO,"Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        
        // TODO: If needed, close connections or release resources related to any external system or data store.

        // Must call super.shutdown()
        super.shutdown();
    }
     */    
	public static final String DESC = 
			"This operator processes RPC requests and thier corresponsing response, using a RabbitMQ sanctioned pattern. " + //$NON-NLS-1$
			"The client creates a private response queue on startup, the request includes a message, responseQueue and coorelationID." + //$NON-NLS-1$
			"This operator excretes the message and a corrolationId. On completion of processing by Streams the results are returned " + //$NON-NLS-1$
			"to the operator's input port with the correlationId. The message is placed on the responseQueue with the coorelationID." + //$NON-NLS-1$
			"The broker is assumed to be already configured and running. " + //$NON-NLS-1$
			"\\n\\n" + //$NON-NLS-1$
			"The incoming stream, with the request, has two required attributes: message, correlationId." + //$NON-NLS-1$
			"The outgoing streams, with the completed response has two required attributes: message, correlationId. " +
			"The outgoing correlationId value must be the same as the incoming correlationId. " + //$NON-NLS-1$
			"\\n\\n" + //$NON-NLS-1$
			"The exchange name, queue name, and routing key can be specified using parameters. " + //$NON-NLS-1$
			"If a specified exchange does not exist, it will be created as a non-durable exchange. " +  //$NON-NLS-1$
			"All exchanges created by this operator are non-durable and auto-delete."  +   //$NON-NLS-1$
			"Response messages are non-persistent and sending will only be attempted once by default. " +  //$NON-NLS-1$
			"This behavior can be modified using the deliveryMode and maxMessageSendRetries parameters. " +  //$NON-NLS-1$
			"\\n\\n**Audience**" +  //$NON-NLS-1$
			"REST requests, the message is a json formatted JSON message and the response message will be http based as well. " +  //$NON-NLS-1$
			"\\n\\n**Notes**" +  //$NON-NLS-1$
			"\\n\\n  **o** Corrupting the correlationId will result in the request timing out. " + 
			"\\n\\n  **o** One correlationId can only be used once, subsequent messages will not be transmitted. " + 
			"\\n\\n**Behavior in a Consistent Region**" +  //$NON-NLS-1$
			"\\nThis operator can participate in a consistent region. It cannot be the start of a consistent region. " +  //$NON-NLS-1$
			RabbitMQBaseOper.BASE_DESC
			;    
}
