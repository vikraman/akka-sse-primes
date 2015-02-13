import akka.actor.{ Actor, ActorLogging, ActorSystem, Props }
import akka.cluster.Cluster
import akka.contrib.pattern.{ DistributedPubSubExtension, DistributedPubSubMediator }
import akka.event.{ Logging, LoggingAdapter }
import akka.http.Http
import akka.http.marshalling.ToResponseMarshallable
import akka.http.model.StatusCodes._
import akka.http.server.Directives._
import akka.stream.{ ActorFlowMaterializer, FlowMaterializer }
import akka.stream.actor.{ ActorPublisher, ActorPublisherMessage, ActorSubscriber, ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.stream.scaladsl.{ Sink, Source }
import com.typesafe.config.{ Config, ConfigFactory }
import de.heikoseeberger.akkasse.EventStreamMarshalling._
import de.heikoseeberger.akkasse.ServerSentEvent
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.forkjoin.ThreadLocalRandom

trait Service {

  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: FlowMaterializer

  def config: Config
  val logger: LoggingAdapter

  val routes = {
    logRequestResult("akka-sse-primes") {
      complete {
        Source[ServerSentEvent](EventSubscriber.props)
      }
    }
  }

  val primeSource: Source[Int] =
    Source(() => Iterator.continually(
      ThreadLocalRandom.current().nextInt(1000000)
    )).filter(rnd => isPrime(rnd))
      .filter(prime => isPrime(prime + 2))
      .filter(prime => isPrime(prime + 6))
      .filter(prime => isPrime(prime + 8))

  def isPrime(n: Int): Boolean = {
    if (n <= 1) false
    else if (n == 2) true
    else !(2 to (n - 1)).exists(x => n % x == 0)
  }
}

object AkkaSseService extends App with Service {

  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorFlowMaterializer()

  override val config = ConfigFactory.load()
  override val logger = Logging(system, getClass)

  Cluster(system).registerOnMemberUp {
    primeSource.map(Events.Prime).runWith(Sink(EventPublisher.props))
  }

  Http().bind(
    interface = config.getString("http.interface"),
    port = config.getInt("http.port")
  ).startHandlingWith(routes)
}

object Events {

  trait Event
  case class Prime(p: Int) extends Event

  import scala.language.implicitConversions
  implicit def eventToSse(event: Event): ServerSentEvent =
    event match {
      case Prime(p) => ServerSentEvent(p.toString, "prime")
    }
}

class EventSubscriber extends ActorPublisher[ServerSentEvent] with ActorLogging {

  import ActorPublisherMessage._
  import DistributedPubSubMediator.{ Subscribe, SubscribeAck }
  val mediator = DistributedPubSubExtension(context.system).mediator

  mediator ! Subscribe("primes", self)

  def receive = {
    case SubscribeAck(Subscribe("primes", None, self)) => context become ready
  }

  def ready: Actor.Receive = {
    case e @ Events.Prime(p) if isActive && totalDemand > 0 => onNext(e)
    case Cancel => context.stop(self)
  }
}

object EventSubscriber {
  def props = Props(classOf[EventSubscriber])
}

class EventPublisher extends ActorSubscriber with ActorLogging {

  import ActorSubscriberMessage._
  import DistributedPubSubMediator.Publish
  val mediator = DistributedPubSubExtension(context.system).mediator

  def requestStrategy = WatermarkRequestStrategy(42)

  def receive = {
    case OnNext(prime) => mediator ! Publish("primes", prime)
    case OnComplete | OnError => context.stop(self)
  }
}

object EventPublisher {
  def props = Props(classOf[EventPublisher])
}
