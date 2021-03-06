/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.tutorial.first.java;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.InternalActorRef;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.japi.Creator;
import akka.routing.*;

import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;

public class Pi {

    public static void main(String[] args) throws Exception {
        Pi pi = new Pi();
        pi.calculate(4, 10000, 10000);
    }

    // ====================
    // ===== Messages =====
    // ====================
    static class Calculate {
    }

    static class Work {
        private final int start;
        private final int nrOfElements;

        public Work(int start, int nrOfElements) {
            this.start = start;
            this.nrOfElements = nrOfElements;
        }

        public int getStart() {
            return start;
        }

        public int getNrOfElements() {
            return nrOfElements;
        }
    }

    static class Result {
        private final double value;

        public Result(double value) {
            this.value = value;
        }

        public double getValue() {
            return value;
        }
    }

    // ==================
    // ===== Worker =====
    // ==================
    public static class Worker extends UntypedActor {

        // define the work
        private double calculatePiFor(int start, int nrOfElements) {
            double acc = 0.0;
            for (int i = start * nrOfElements; i <= ((start + 1) * nrOfElements - 1); i++) {
                acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1);
            }
            return acc;
        }

        // message handler
        public void onReceive(Object message) {
            if (message instanceof Work) {
                Work work = (Work) message;

                // perform the work
                double result = calculatePiFor(work.getStart(), work.getNrOfElements());

                // reply with the result
                getSender().tell(new Result(result));

            } else throw new IllegalArgumentException("Unknown message [" + message + "]");
        }
    }

    // ==================
    // ===== Master =====
    // ==================
    public static class Master extends UntypedActor {
        private final int nrOfMessages;
        private final int nrOfElements;
        private final CountDownLatch latch;

        private double pi;
        private int nrOfResults;
        private long start;

        private ActorRef router;

        public Master(final int nrOfWorkers, int nrOfMessages, int nrOfElements, CountDownLatch latch) {
            this.nrOfMessages = nrOfMessages;
            this.nrOfElements = nrOfElements;
            this.latch = latch;
            Creator<Router> routerCreator = new Creator<Router>() {
                public Router create() {
                    return new RoundRobinRouter(getContext().dispatcher(), new akka.actor.Timeout(-1));
                }
            };
            LinkedList<ActorRef> actors = new LinkedList<ActorRef>() {
                {
                    for (int i = 0; i < nrOfWorkers; i++) add(getContext().actorOf(Worker.class));
                }
            };
			// FIXME routers are intended to be used like this
            RoutedProps props = new RoutedProps(routerCreator, new LocalConnectionManager(actors), new akka.actor.Timeout(-1), true);
            router = new RoutedActorRef(getContext().system(), props, (InternalActorRef) getSelf(), "pi");
        }

        // message handler
        public void onReceive(Object message) {

            if (message instanceof Calculate) {
                // schedule work
                for (int start = 0; start < nrOfMessages; start++) {
                    router.tell(new Work(start, nrOfElements), getSelf());
                }

            } else if (message instanceof Result) {

                // handle result from the worker
                Result result = (Result) message;
                pi += result.getValue();
                nrOfResults += 1;
                if (nrOfResults == nrOfMessages) getSelf().stop();

            } else throw new IllegalArgumentException("Unknown message [" + message + "]");
        }

        @Override
        public void preStart() {
            start = System.currentTimeMillis();
        }

        @Override
        public void postStop() {
            // tell the world that the calculation is complete
            System.out.println(String.format(
                    "\n\tPi estimate: \t\t%s\n\tCalculation time: \t%s millis",
                    pi, (System.currentTimeMillis() - start)));
            latch.countDown();
        }
    }

    // ==================
    // ===== Run it =====
    // ==================
    public void calculate(final int nrOfWorkers, final int nrOfElements, final int nrOfMessages)
            throws Exception {
        final ActorSystem system = ActorSystem.create();

        // this latch is only plumbing to know when the calculation is completed
        final CountDownLatch latch = new CountDownLatch(1);

        // create the master
        ActorRef master = system.actorOf(new UntypedActorFactory() {
            public UntypedActor create() {
                return new Master(nrOfWorkers, nrOfMessages, nrOfElements, latch);
            }
        });

        // start the calculation
        master.tell(new Calculate());

        // wait for master to shut down
        latch.await();

        // Shut down the system
        system.stop();
    }
}
