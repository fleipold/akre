package org.programmiersportgruppe.redis

import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.duration._

import akka.actor.ActorRefFactory
import akka.routing._
import akka.util.{ByteString, Timeout}


class RedisClientException(message: String, cause: Throwable) extends Exception(message, cause) {
  def this(message: String) = this(message, null)
}

case class ErrorReplyException(command: Command, reply: RError)
  extends RedisClientException(s"Error reply received: ${reply.asString}\nFor command: $command\nSent as: ${command.serialised.utf8String}")

case class UnexpectedReplyException(command: Command, reply: RSuccessValue)
  extends RedisClientException(s"Unexpected reply received: ${reply}\nFor command: $command")

case class RequestExecutionException(command: Command, cause: Throwable)
  extends RedisClientException(s"Error while executing command [$command]: ${cause.getMessage}", cause)


class RedisClient(actorRefFactory: ActorRefFactory, hostName: String, hostPort: Int, connectTimeout: Timeout, requestTimeout: Timeout, numberOfConnections: Int, connectionSetupCommands: Seq[Command] = Nil, poolActorName: String = "akre-redis-pool") {
  def this(actorRefFactory: ActorRefFactory, hostName: String, hostPort: Int, connectTimeout: Timeout, requestTimeout: Timeout, numberOfConnections: Int, initialClientName: String, poolActorName: String) =
    this(actorRefFactory, hostName, hostPort, connectTimeout, requestTimeout, numberOfConnections, Seq(CLIENT_SETNAME(initialClientName)), poolActorName)

  import akka.pattern.ask
  import actorRefFactory.dispatcher

  implicit private val timeout = requestTimeout

  private val poolActor = {
    val connection = RedisConnectionActor.props(hostName, hostPort, connectionSetupCommands, Some(Ready))

    val pool = ResilientPool.props(
      childProps = connection,
      size = numberOfConnections,
      creationCircuitBreakerLogic = new CircuitBreakerLogic(
        consecutiveFailureTolerance = 2,
        openPeriods = OpenPeriodStrategy.doubling(100.milliseconds, 1.minute),
        halfOpenTimeout = connectTimeout
      ),
      routingLogic = RoundRobinRoutingLogic()
    )

    actorRefFactory.actorOf(pool, poolActorName)
  }

  def waitUntilConnected(timeout: FiniteDuration, minConnections: Int = 1) {
    require(minConnections <= numberOfConnections)
    val deadline = timeout.fromNow
    val sleepMillis = Math.min(timeout.toMillis / 10, 30)
    while(deadline.timeLeft match {
      case remaining if remaining > Duration.Zero =>
        Await.result(poolActor.ask(GetRoutees)(remaining), timeout) match {
          case Routees(routees) => routees.length < minConnections
        }
      case _ => throw new TimeoutException(s"Exceeded $timeout timeout while waiting for at least $minConnections connections")
    })
      Thread.sleep(sleepMillis)
  }

  /**
   * Executes a command.
   *
   * @param command the command to be executed
   * @return a non-error reply from the server
   * @throws ErrorReplyException if the server gives an error reply
   * @throws AskTimeoutException if the connection pool fails to deliver a reply within the requestTimeout
   */
  def execute(command: Command): Future[RSuccessValue] = (poolActor ? command).transform({
    case (`command`, r: RSuccessValue) => r
    case (`command`, e: RError)        => throw new ErrorReplyException(command, e)
  }, {
    case e: Throwable => new RequestExecutionException(command, e)
  })

  /**
   * Executes a command that is expected to cause the server to close the connection.
   *
   * @param command the command to be executed
   * @return a unit future that completes when the connection closes
   * @throws AskTimeoutException if the connection pool fails to deliver a reply within the requestTimeout
   */
  def executeConnectionClose(command: Command with ConnectionCloseExpected): Future[Unit] = (poolActor ? command).transform({
    case () => ()
  }, {
    case e: Throwable => new RequestExecutionException(command, e)
  })

  /**
   * Executes a command and extracts an optional akka.util.ByteString from the bulk reply that is expected.
   *
   * @param command the bulk reply command to be executed
   * @throws ErrorReplyException      if the server gives an error reply
   * @throws AskTimeoutException      if the connection pool fails to deliver a reply within the requestTimeout
   * @throws UnexpectedReplyException if the server gives a proper non-bulk reply
   */
  def executeByteString(command: Command with BulkExpected): Future[Option[ByteString]] =
    execute(command) map {
      case RBulkString(data) => data
      case reply             => throw new UnexpectedReplyException(command, reply)
    }

  /**
   * Executes a command and extracts an optional String from the UTF-8 encoded bulk reply that is
   * expected.
   *
   * @param command the bulk reply command to be executed
   * @throws ???                      if the reply cannot be decoded as UTF-8
   * @throws ErrorReplyException      if the server gives an error reply
   * @throws AskTimeoutException      if the connection pool fails to deliver a reply within the requestTimeout
   * @throws UnexpectedReplyException if the server gives a proper non-bulk reply
   */
  def executeString(command: Command with BulkExpected): Future[Option[String]] =
    execute(command) map {
      case RBulkString(data) => data.map(_.utf8String)
      case reply             => throw new UnexpectedReplyException(command, reply)
    }

  /**
   * Executes a command and extracts a Long from the int reply that is expected.
   *
   * @param command the int reply command to be executed
   * @throws ErrorReplyException      if the server gives an error reply
   * @throws AskTimeoutException      if the connection pool fails to deliver a reply within the requestTimeout
   * @throws UnexpectedReplyException if the server gives a proper non-bulk reply
   */
  def executeLong(command: Command with IntegerExpected): Future[Long] =
    execute(command) map {
      case RInteger(value) => value
      case reply           => throw new UnexpectedReplyException(command, reply)
    }

  /**
   * Executes a command and verifies that it gets an "OK" status reply.
   *
   * @param command the ok status reply command to be executed
   * @throws ErrorReplyException      if the server gives an error reply
   * @throws AskTimeoutException      if the connection pool fails to deliver a reply within the requestTimeout
   * @throws UnexpectedReplyException if the server gives a proper reply that is not StatusReply("OK")
   */
  def executeSuccessfully(command: Command with OkStatusExpected): Future[Unit] =
    execute(command) map {
      case RSimpleString.OK => ()
      case reply            => throw new UnexpectedReplyException(command, reply)
    }

  /**
   * Stops the connection pool used by the client.
   * @throws AskTimeoutException if the connection pool fails to stop within 30 seconds
   */
  def shutdown(): Future[Unit] = {
    akka.pattern.gracefulStop(poolActor, 30.seconds).map(_ => ())
  }

//  def executeBoolean(command: RedisCommand[IntegerReply]): Future[Boolean] = executeAny(command) map { case IntegerReply(0) => false; case IntegerReply(1) => true }
//  def executeBytes(command: RedisCommand[BulkReply]): Future[Option[ByteString]] = executeAny(command) map { case BulkReply(data) => data }
//  def executeString(command: RedisCommand[BulkReply]): Future[Option[String]] = executeAny(command) map { case BulkReply(data) => data.map(_.utf8String) }
}
