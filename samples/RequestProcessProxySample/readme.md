# MqStreamsProxy : MQRabbit/Streams High Throughput WEB Integration

Example Streams integrated into a large WEB site using the RabbitMQ toolkit. The toolkit
and sample are avlable on GitHub. 
  
RabbitMQ is Message Hub used by large web sites to process requests. Web requests are  received, 
decomposed, distributed to components (databases, appservers, analytics servers), processed, 
 formatted and returned to the requester. Requests move though the site using Message Hub's that
 handle the scaling and fallback processing that are enviable occur in large systems. 

The following diagram illustrates such a system. 

![alt text](awsDesign.jpg)
  
In such an environment Streams is used to: 
- Drive audio to text processing.
- Score images in conjunction with SPSS.
- Characterize video feeds.
- Monitor and report pump pressure, speed and temperature

To run in this environment we're using the RPC Pattern. A pattern that allows requestors and Streams to be
added and removed independently. 

This is a Streams application communicating over RabbitMQ Message Bus using the RabbitMQRequestResponse()
operator. The client, a jetty app server that driven with web requests (browser or curl.  The server, Streams application
that can handle various requests. You can add/remove clients and servers without affecting the overall processing. 


## Context 

The sample focuses on the messaging to Streams using RabbitMQ toolkit. As 
load fluctuates new requestors and Streams instances can be added and dropped. 
The Sample requestor is a asynchronous J2EE servlet, requests are made with a
a browser or curl. Adding new requester involves adding a new servlet. 


We've implemented the a Message Bus RPC pattern described[here](https://www.rabbitmq.com/tutorials/tutorial-six-python.html). 
The server side (Streams) uses the  RabbitMQRequestResponse() operator of the RabbitMQ tookit. 
 
Focus on components : 
![alt text](ibmView.jpg)

As the load changes new servlets and Streams instance are brought up and down. The load balancer 
distrbutes the request across the web servers. A monitoring process is responsible for 
bringing the components up and down. 
- The sample uses an J2EE Server (Jetty) and AMQP message (RabbitMQ) message broker. The message broker enables more clients (WebServer) and/or requests processors (Streams) to be added independently. 
- The design allows new clients (application servers) and server (Streams instances) to be added and removed independently. The only well know resource is the queue that all 
requests from the client to server use. 
- The J2EE application uses the J2EEv3 asynchronous processing feature which enables multiple web requests to be outstanding at a time. The Streams example application
 is written as a pipeline.  
- The Streams portion uses the RabbitMQRequestResponse() operator that accepts a number of commands for testing. Commands are 
passed on the URL. The sample application's flow: 
![alt text](streamsFlow.jpg)



# MqStreamProxy Sample
The sample consists of Jetty Server communicating with Streams via RabbitMQ.  

This walks through bringing up the sample and running a test. Tests are invoked using curl to the servlet,
the servlet passes the reqeust to Streams that generates a response and communicates it back to 
the originating client. 

The components can be scaled independently by adding/removing Servlets or Streams applications as load fluctuates. 


### Directories : 

* RabbitMQRabbitRestServer : A Steams application that executes commands: sleep, fill and mirror. Commands and the corresponding results are
communicated via RabbitMQ. 
* MqStreamsProxy : A J2EE Servlet that accepts REST requests, transmits them via MQRabbit which are processed by the Streams application. The 
processing results are returned following the opposite path. 




## Components 

* [Streams QSE](https://www.ibm.com/developerworks/downloads/im/streamsquick/index.html) : This is includes Streams 
development environment where the sample code can be inspected and modified.
* [RabbitMQ](https://www.rabbitmq.com/download.html) download site. Must be installed and running. 
* Maven : The demo uses maven to install the Jetty server and run the war file.
* [JettyServer]:(https://www.eclipse.org/jetty/) : J2EE server used for demo. 
* Optional - [LibertyServer](https://developer.ibm.com/wasdev/downloads/liberty-profile-using-eclipse/) : Includes the Eclipse development environment where the sample code can be inspected and modified. Used for development. 


# Bring up the components. 

## Install RabbitMQ

Install RabbitMQ, instructions can be found [here](https://gist.github.com/ravibhure/92e780ecc850cd5ab0ab) 

## Bring up RabbitMQ

To bring up the RabbitMQ
```
sudo service rabbitmq-server start
```
Verify that the server is up...
```bash
sudo rabbitmqctl status
```
Output will describe the state of the system. 

I use the web interface to monitor RabbitMQ. You must enable the iterface once, use this command:
```bash
sudo rabbitmq-plugins enable rabbitmq_managment
```

Access the web console with [http://localhost:15672/#/]([http://localhost:15672/#/), the default
username/password is guest/guest.


## Bring up Streams application

### Build the Toolkit.

```bash
cd ... streamsx.rabbitmq/comstreamsx.rabbitmq
ant clean
ant
```
### Build and run the Streams application. 

Clean and build the Stream application.
```bash
cd samples/RequestProcsSample/RabbitMQRestServe
make clean
make
```
Run the Streams application in Standalone mode.
```bash
make stand
```


### Bring up Servlet

The provided maven pom file will install Jetty, build and run servlet using Jetty. 

```
cd ... samples/RequestProcessProxySample/MqStreamsProxy
mvn clean
mvn package
mvn war:war
mvn jetty:run-war
```


# Demo  

REST requests to the application can be made with curl from 
the command line. The request has the following format:

```bash
curl "http://localhost:9080/MqStreamsProxy/MqStreamsProxy?fill=<fillCnt>&sleep=<sleepSec>&amp;mirror=0"
```

* fillCnt : number of 'A's to return 
* sleepSec : number of seconds to wait, simulates computation 
* mirror : reflect back request. 


### Request 

```bash
curl "http://localhost:9080/MqStreamsProxy/MqStreamsProxy?fill=5&amp;sleep=5&amp;mirror=0"
```

## Response
The result request returns after 5 seconds, note that the 5 'A's of fill. 

```
{"sequenceNumber":"44","request":"fill=5&amp;sleep=1&amp;mirror=0","method":"GET","timeString":"","contextPath":"/MqStreamsProxy","block":"1","fill":"AAAAA","pathInfo":"/MqStreamsProxy"}
```



## Other
* You can invoke mulitple clients and adjust the parameters.
* You can start muliple Streams applications if you want simultaneous processing. 

# Addendums 

## Servlet Addendum 
The servlet has a number of parameters that you may want change for your enviroment, these values
can be set in the web.xml.

* defaultRequestQueue : common queue that all servers are listening on
* log : enable logging
* timeout : time to timeout a request
* queueHost : host rabbitmq is running on
* username : rabbitmq username
* password : rabbitmq password
* asyn-supported : leave as true
* port : port the servlet listens on


Deploy the resulting application using
   ... MqStreamProxy/target/MqStreamProxy-1.0.war
   
## Sample Addendum 
All web sites are different, adapt the code to fit your environment. 

## RabbitMQ Addendum

* By default, the guest user is prohibited from connecting to the broker remotely; it can only connect over a loopback interface (i.e. localhost). To remedy the situation refer to  : https://www.rabbitmq.com/access-control.html. 


