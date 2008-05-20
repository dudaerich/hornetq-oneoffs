/*
 * JBoss, Home of Professional Open Source
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jboss.messaging.core.remoting.impl.mina;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import org.apache.mina.common.AttributeKey;
import org.apache.mina.common.IoHandlerAdapter;
import org.apache.mina.common.IoSession;
import org.jboss.messaging.core.exception.MessagingException;
import org.jboss.messaging.core.logging.Logger;
import org.jboss.messaging.core.remoting.Packet;
import org.jboss.messaging.core.remoting.PacketDispatcher;
import org.jboss.messaging.core.remoting.PacketHandlerRegistrationListener;
import org.jboss.messaging.core.remoting.PacketReturner;
import org.jboss.messaging.util.OrderedExecutorFactory;

/**
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 *
 * @version <tt>$Revision$</tt>
 *
 */
public class MinaHandler extends IoHandlerAdapter implements
      PacketHandlerRegistrationListener
{
   // Constants -----------------------------------------------------

   private static final Logger log = Logger.getLogger(MinaHandler.class);

   private static final AttributeKey BLOCKED = new AttributeKey(MinaHandler.class, "blocked");

   private static boolean trace = log.isTraceEnabled();

   // Attributes ----------------------------------------------------

   private final PacketDispatcher dispatcher;

   private final CleanUpNotifier failureNotifier;

   private final boolean closeSessionOnExceptionCaught;

   private final OrderedExecutorFactory executorFactory;

   // Note! must use ConcurrentMap here to avoid race condition
   private final ConcurrentMap<Long, Executor> executors = new ConcurrentHashMap<Long, Executor>();

   private final long blockTimeout;

   //TODO - this is screwed - I want this to be zero, but unfortunately in messageSent, the current
   //messages bytes haven't been subtracted so this won't work!!
   private final long bytesLow;

   private final long bytesHigh;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------
   public MinaHandler(final PacketDispatcher dispatcher,
                      final ExecutorService executorService,
                      final CleanUpNotifier failureNotifier,
                      final boolean closeSessionOnExceptionCaught,
                      final boolean useExecutor,
                      final long blockTimeout,
                      final long bytesLow,
                      final long bytesHigh)
   {
      assert dispatcher != null;
      assert executorService != null;

      this.blockTimeout = blockTimeout;
      this.bytesLow = bytesLow;
      this.bytesHigh = bytesHigh;

      this.dispatcher = dispatcher;
      this.failureNotifier = failureNotifier;
      this.closeSessionOnExceptionCaught = closeSessionOnExceptionCaught;
      if (useExecutor)
      {
         executorFactory = new OrderedExecutorFactory(executorService);
      }
      else
      {
         executorFactory = null;
      }
      this.dispatcher.setListener(this);
   }

   // Public --------------------------------------------------------

   // PacketHandlerRegistrationListener implementation --------------

   public void handlerRegistered(final long handlerID)
   {
      // do nothing on registration
   }

   public void handlerUnregistered(final long handlerID)
   {
      executors.remove(handlerID);
   }

   // IoHandlerAdapter overrides ------------------------------------

   @Override
   public void sessionCreated(IoSession session) throws Exception {
      // Initialize the default attributes.
      session.setAttribute(BLOCKED, Boolean.FALSE);
   }

   @Override
   public void exceptionCaught(final IoSession session, final Throwable cause)
         throws Exception
   {
      log.error("caught exception " + cause + " for session " + session, cause);

      if (failureNotifier != null)
      {
         long serverSessionID = session.getId();
         MessagingException me = new MessagingException(
               MessagingException.INTERNAL_ERROR, "unexpected exception");
         me.initCause(cause);
         failureNotifier.fireCleanup(serverSessionID, me);
      }
      if (closeSessionOnExceptionCaught)
      {
         session.close();
      }
   }

   @Override
   public void messageReceived(final IoSession session, final Object message)
   throws Exception
   {
      final Packet packet = (Packet) message;

      if (executorFactory != null)
      {
         long executorID = packet.getExecutorID();

         Executor executor = executors.get(executorID);
         if (executor == null)
         {
            executor = executorFactory.getOrderedExecutor();

            Executor oldExecutor = executors.putIfAbsent(executorID, executor);

            if (oldExecutor != null)
            {
               //Avoid race
               executor = oldExecutor;
            }
         }

         executor.execute(new Runnable()
         {
            public void run()
            {
               try
               {
                  messageReceivedInternal(session, packet);
               }
               catch (Exception e)
               {
                  log.error("unexpected error", e);
               }
            }
         });
      }
      else
      {
         messageReceivedInternal(session, packet);
      }
   }

   @Override
   public synchronized void messageSent(final IoSession session, final Object message) throws Exception
   {
      boolean blocked = (Boolean) session.getAttribute(BLOCKED);
      if (blocked)
      {
         long bytes = session.getScheduledWriteBytes();

         if (bytes <= bytesLow)
         {
            session.setAttribute(BLOCKED, Boolean.FALSE);

            // Resume read operation if it's suspended.
            if (!session.getTrafficMask().isReadable()) {
               session.resumeRead();
            }

            //Note that we need to notify all since there may be more than one thread waiting on this
            //E.g. the response from a blocking acknowledge and a delivery
            notifyAll();
         }
      }
   }

   public synchronized void checkWrite(final IoSession session) throws Exception
   {
      while (session.getScheduledWriteBytes() >= bytesHigh)
      {
         session.setAttribute(BLOCKED, Boolean.TRUE);

         long start = System.currentTimeMillis();

         long toWait = blockTimeout;

         do
         {
            wait(toWait);

            if (session.getScheduledWriteBytes() < bytesHigh)
            {
               break;
            }

            long now = System.currentTimeMillis();

            toWait -= now - start;

            start = now;
         }
         while (toWait > 0);

         if (toWait <= 0)
         {
            throw new IllegalStateException("Timed out waiting for MINA queue to free");
         }
      }
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   private void messageReceivedInternal(final IoSession session, Packet packet)
         throws Exception
   {
      PacketReturner returner;

      final Thread ioThread = Thread.currentThread();

      if (packet.getResponseTargetID() != Packet.NO_ID_SET)
      {
         returner = new PacketReturner()
         {
            public void send(Packet p) throws Exception
            {
               // Make sure we don't block the I/O thread.
               if (Thread.currentThread() != ioThread)
               {
                  try
                  {
                     checkWrite(session);
                  }
                  catch (Exception e)
                  {
                     log.error("Failed to acquire sem", e);
                  }
               }
               else
               {
                  // If we are running this in the I/O thread, we should not block, because
                  // blocking will prevent the decrement of the scheduledWriteBytes() property.
                  // Instead, we should disable the read operation temporarily so we don't
                  // need to handle incoming messages which causes write queue overflow.

                  if (session.getScheduledWriteBytes() >= bytesHigh)
                  {
                     // Will resume on messageSent()
                     session.suspendRead();
                  }
               }

               dispatcher.callFilters(p);

               session.write(p);
            }

            public long getSessionID()
            {
               return session.getId();
            }

            public String getRemoteAddress()
            {
               return session.getRemoteAddress().toString();
            }
         };
      }
      else
      {
         returner = null;
      }

      if (trace) {
        log.trace("received packet " + packet);
    }

      dispatcher.dispatch(packet, returner);
   }

   // Inner classes -------------------------------------------------
}
