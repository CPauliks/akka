/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch

import akka.AkkaException
import java.util.{ Comparator, PriorityQueue, Queue }
import akka.util._
import akka.actor.{ ActorCell, ActorRef }
import java.util.concurrent._
import annotation.tailrec
import akka.event.Logging.Error

class MessageQueueAppendFailedException(message: String, cause: Throwable = null) extends AkkaException(message, cause)

object Mailbox {

  type Status = Int

  /*
   * the following assigned numbers CANNOT be changed without looking at the code which uses them!
   */

  // primary status: only first three
  final val Open = 0 // _status is not initialized in AbstractMailbox, so default must be zero!
  final val Suspended = 1
  final val Closed = 2
  // secondary status: Scheduled bit may be added to Open/Suspended
  final val Scheduled = 4

  // mailbox debugging helper using println (see below)
  // FIXME RK take this out before release (but please leave in until M2!)
  final val debug = false
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class Mailbox(val actor: ActorCell) extends MessageQueue with SystemMessageQueue with Runnable {
  import Mailbox._

  @volatile
  protected var _statusDoNotCallMeDirectly: Status = _ //0 by default

  @volatile
  protected var _systemQueueDoNotCallMeDirectly: SystemMessage = _ //null by default

  @inline
  final def status: Mailbox.Status = Unsafe.instance.getIntVolatile(this, AbstractMailbox.mailboxStatusOffset)

  @inline
  final def shouldProcessMessage: Boolean = (status & 3) == Open

  @inline
  final def isSuspended: Boolean = (status & 3) == Suspended

  @inline
  final def isClosed: Boolean = status == Closed

  @inline
  final def isScheduled: Boolean = (status & Scheduled) != 0

  @inline
  protected final def updateStatus(oldStatus: Status, newStatus: Status): Boolean =
    Unsafe.instance.compareAndSwapInt(this, AbstractMailbox.mailboxStatusOffset, oldStatus, newStatus)

  @inline
  protected final def setStatus(newStatus: Status): Unit =
    Unsafe.instance.putIntVolatile(this, AbstractMailbox.mailboxStatusOffset, newStatus)

  /**
   * set new primary status Open. Caller does not need to worry about whether
   * status was Scheduled or not.
   */
  @tailrec
  final def becomeOpen(): Boolean = status match {
    case Closed ⇒ setStatus(Closed); false
    case s      ⇒ updateStatus(s, Open | s & Scheduled) || becomeOpen()
  }

  /**
   * set new primary status Suspended. Caller does not need to worry about whether
   * status was Scheduled or not.
   */
  @tailrec
  final def becomeSuspended(): Boolean = status match {
    case Closed ⇒ setStatus(Closed); false
    case s      ⇒ updateStatus(s, Suspended | s & Scheduled) || becomeSuspended()
  }

  /**
   * set new primary status Closed. Caller does not need to worry about whether
   * status was Scheduled or not.
   */
  @tailrec
  final def becomeClosed(): Boolean = status match {
    case Closed ⇒ setStatus(Closed); false
    case s      ⇒ updateStatus(s, Closed) || becomeClosed()
  }

  /**
   * Set Scheduled status, keeping primary status as is.
   */
  @tailrec
  final def setAsScheduled(): Boolean = {
    val s = status
    /*
     * only try to add Scheduled bit if pure Open/Suspended, not Closed or with
     * Scheduled bit already set (this is one of the reasons why the numbers
     * cannot be changed in object Mailbox above)
     */
    if (s <= Suspended) updateStatus(s, s | Scheduled) || setAsScheduled()
    else false
  }

  /**
   * Reset Scheduled status, keeping primary status as is.
   */
  @tailrec
  final def setAsIdle(): Boolean = {
    val s = status
    /*
     * only try to remove Scheduled bit if currently Scheduled, not Closed or
     * without Scheduled bit set (this is one of the reasons why the numbers
     * cannot be changed in object Mailbox above)
     */

    updateStatus(s, s & ~Scheduled) || setAsIdle()
  }

  /*
   * AtomicReferenceFieldUpdater for system queue
   */
  protected final def systemQueueGet: SystemMessage =
    Unsafe.instance.getObjectVolatile(this, AbstractMailbox.systemMessageOffset).asInstanceOf[SystemMessage]
  protected final def systemQueuePut(_old: SystemMessage, _new: SystemMessage): Boolean =
    Unsafe.instance.compareAndSwapObject(this, AbstractMailbox.systemMessageOffset, _old, _new)

  final def canBeScheduledForExecution(hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = status match {
    case Open | Scheduled ⇒ hasMessageHint || hasSystemMessageHint || hasSystemMessages || hasMessages
    case Closed           ⇒ false
    case _                ⇒ hasSystemMessageHint || hasSystemMessages
  }

  final def run = {
    try {
      if (!isClosed) { //Volatile read, needed here
        processAllSystemMessages() //First, deal with any system messages
        processMailbox() //Then deal with messages
      }
    } finally {
      setAsIdle() //Volatile write, needed here
      dispatcher.registerForExecution(this, false, false)
    }
  }

  /**
   * Process the messages in the mailbox
   *
   * @return true if the processing finished before the mailbox was empty, due to the throughput constraint
   */
  private final def processMailbox() {
    if (shouldProcessMessage) {
      var nextMessage = dequeue()
      if (nextMessage ne null) { //If we have a message
        if (dispatcher.isThroughputDefined) { //If we're using throughput, we need to do some book-keeping
          var processedMessages = 0
          val deadlineNs = if (dispatcher.isThroughputDeadlineTimeDefined) System.nanoTime + dispatcher.throughputDeadlineTime.toNanos else 0
          do {
            if (debug) println(actor.self + " processing message " + nextMessage)
            actor invoke nextMessage
            processAllSystemMessages() //After we're done, process all system messages

            nextMessage = if (shouldProcessMessage) { // If we aren't suspended, we need to make sure we're not overstepping our boundaries
              processedMessages += 1
              if ((processedMessages >= dispatcher.throughput) || (dispatcher.isThroughputDeadlineTimeDefined && System.nanoTime >= deadlineNs)) // If we're throttled, break out
                null //We reached our boundaries, abort
              else dequeue //Dequeue the next message
            } else null //Abort
          } while (nextMessage ne null)
        } else { //If we only run one message per process
          actor invoke nextMessage //Just run it
          processAllSystemMessages() //After we're done, process all system messages
        }
      }
    }
  }

  final def processAllSystemMessages() {
    var nextMessage = systemDrain()
    try {
      while (nextMessage ne null) {
        if (debug) println(actor.self + " processing system message " + nextMessage + " with children " + actor.childrenRefs)
        actor systemInvoke nextMessage
        nextMessage = nextMessage.next
        // don’t ever execute normal message when system message present!
        if (nextMessage eq null) nextMessage = systemDrain()
      }
    } catch {
      case e ⇒
        actor.system.eventStream.publish(Error(e, actor.self.path.toString, "exception during processing system messages, dropping " + SystemMessage.size(nextMessage) + " messages!"))
        throw e
    }
  }

  @inline
  final def dispatcher: MessageDispatcher = actor.dispatcher

  /**
   * Overridable callback to clean up the mailbox,
   * called when an actor is unregistered.
   * By default it dequeues all system messages + messages and ships them to the owning actors' systems' DeadLetterMailbox
   */
  protected[dispatch] def cleanUp(): Unit = if (actor ne null) {
    val dlq = actor.systemImpl.deadLetterMailbox
    if (hasSystemMessages) {
      var message = systemDrain()
      while (message ne null) {
        // message must be “virgin” before being able to systemEnqueue again
        val next = message.next
        message.next = null
        dlq.systemEnqueue(actor.self, message)
        message = next
      }
    }

    if (hasMessages) {
      var envelope = dequeue
      while (envelope ne null) {
        dlq.enqueue(actor.self, envelope)
        envelope = dequeue
      }
    }
  }
}

trait MessageQueue {
  /*
   * These method need to be implemented in subclasses; they should not rely on the internal stuff above.
   */
  def enqueue(receiver: ActorRef, handle: Envelope)

  def dequeue(): Envelope

  def numberOfMessages: Int

  def hasMessages: Boolean
}

trait SystemMessageQueue {
  /**
   * Enqueue a new system message, e.g. by prepending atomically as new head of a single-linked list.
   */
  def systemEnqueue(receiver: ActorRef, message: SystemMessage): Unit

  /**
   * Dequeue all messages from system queue and return them as single-linked list.
   */
  def systemDrain(): SystemMessage

  def hasSystemMessages: Boolean
}

trait DefaultSystemMessageQueue { self: Mailbox ⇒

  @tailrec
  final def systemEnqueue(receiver: ActorRef, message: SystemMessage): Unit = {
    assert(message.next eq null)
    if (Mailbox.debug) println(actor.self + " having enqueued " + message)
    val head = systemQueueGet
    /*
     * this write is safely published by the compareAndSet contained within
     * systemQueuePut; “Intra-Thread Semantics” on page 12 of the JSR133 spec
     * guarantees that “head” uses the value obtained from systemQueueGet above.
     * Hence, SystemMessage.next does not need to be volatile.
     */
    message.next = head
    if (!systemQueuePut(head, message)) {
      message.next = null
      systemEnqueue(receiver, message)
    }
  }

  @tailrec
  final def systemDrain(): SystemMessage = {
    val head = systemQueueGet
    if (systemQueuePut(head, null)) SystemMessage.reverse(head) else systemDrain()
  }

  def hasSystemMessages: Boolean = systemQueueGet ne null
}

trait UnboundedMessageQueueSemantics extends QueueBasedMessageQueue {
  final def enqueue(receiver: ActorRef, handle: Envelope): Unit = queue add handle
  final def dequeue(): Envelope = queue.poll()
}

trait BoundedMessageQueueSemantics extends QueueBasedMessageQueue {
  def pushTimeOut: Duration
  override def queue: BlockingQueue[Envelope]

  final def enqueue(receiver: ActorRef, handle: Envelope) {
    if (pushTimeOut.length > 0) {
      queue.offer(handle, pushTimeOut.length, pushTimeOut.unit) || {
        throw new MessageQueueAppendFailedException("Couldn't enqueue message " + handle + " to " + toString)
      }
    } else queue put handle
  }

  final def dequeue(): Envelope = queue.poll()
}

trait QueueBasedMessageQueue extends MessageQueue {
  def queue: Queue[Envelope]
  final def numberOfMessages = queue.size
  final def hasMessages = !queue.isEmpty
}

/**
 * Mailbox configuration.
 */
trait MailboxType {
  def create(receiver: ActorCell): Mailbox
}

/**
 * It's a case class for Java (new UnboundedMailbox)
 */
case class UnboundedMailbox() extends MailboxType {
  override def create(receiver: ActorCell) =
    new Mailbox(receiver) with QueueBasedMessageQueue with UnboundedMessageQueueSemantics with DefaultSystemMessageQueue {
      final val queue = new ConcurrentLinkedQueue[Envelope]()
    }
}

case class BoundedMailbox( final val capacity: Int, final val pushTimeOut: Duration) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  override def create(receiver: ActorCell) =
    new Mailbox(receiver) with QueueBasedMessageQueue with BoundedMessageQueueSemantics with DefaultSystemMessageQueue {
      final val queue = new LinkedBlockingQueue[Envelope](capacity)
      final val pushTimeOut = BoundedMailbox.this.pushTimeOut
    }
}

case class UnboundedPriorityMailbox( final val cmp: Comparator[Envelope]) extends MailboxType {
  override def create(receiver: ActorCell) =
    new Mailbox(receiver) with QueueBasedMessageQueue with UnboundedMessageQueueSemantics with DefaultSystemMessageQueue {
      final val queue = new PriorityBlockingQueue[Envelope](11, cmp)
    }
}

case class BoundedPriorityMailbox( final val cmp: Comparator[Envelope], final val capacity: Int, final val pushTimeOut: Duration) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  override def create(receiver: ActorCell) =
    new Mailbox(receiver) with QueueBasedMessageQueue with BoundedMessageQueueSemantics with DefaultSystemMessageQueue {
      final val queue = new BoundedBlockingQueue[Envelope](capacity, new PriorityQueue[Envelope](11, cmp))
      final val pushTimeOut = BoundedPriorityMailbox.this.pushTimeOut
    }
}

