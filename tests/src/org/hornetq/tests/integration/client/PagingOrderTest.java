/*
 * Copyright 2010 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.tests.integration.client;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.hornetq.api.core.HornetQBuffer;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.core.client.impl.ClientSessionFactoryImpl;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.persistence.impl.journal.OperationContextImpl;
import org.hornetq.core.postoffice.Binding;
import org.hornetq.core.postoffice.Bindings;
import org.hornetq.core.postoffice.impl.LocalQueueBinding;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.Queue;
import org.hornetq.core.server.ServerSession;
import org.hornetq.core.server.impl.QueueImpl;
import org.hornetq.core.settings.impl.AddressSettings;
import org.hornetq.tests.util.ServiceTestBase;

/**
 * A PagingOrderTest.
 * 
 * PagingTest has a lot of tests already. I decided to create a newer one more specialized on Ordering and counters
 *
 * @author clebertsuconic
 *
 *
 */
public class PagingOrderTest extends ServiceTestBase
{

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   private ServerLocator locator;

   public PagingOrderTest(final String name)
   {
      super(name);
   }

   public PagingOrderTest()
   {
      super();
   }

   // Constants -----------------------------------------------------
   private static final Logger log = Logger.getLogger(PagingTest.class);

   private static final int RECEIVE_TIMEOUT = 30000;

   private static final int PAGE_MAX = 100 * 1024;

   private static final int PAGE_SIZE = 10 * 1024;

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   static final SimpleString ADDRESS = new SimpleString("SimpleAddress");

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   @Override
   protected void setUp() throws Exception
   {
      super.setUp();
      locator = createInVMNonHALocator();
   }

   @Override
   protected void tearDown() throws Exception
   {
      locator.close();

      super.tearDown();
   }

   public void testOrder1() throws Throwable
   {
      boolean persistentMessages = true;

      Configuration config = createDefaultConfig();

      config.setJournalSyncNonTransactional(false);

      HornetQServer server = createServer(true, config, PAGE_SIZE, PAGE_MAX, new HashMap<String, AddressSettings>());

      server.start();

      final int messageSize = 1024;

      final int numberOfMessages = 500;

      try
      {
         ServerLocator locator = createInVMNonHALocator();

         locator.setClientFailureCheckPeriod(1000);
         locator.setConnectionTTL(2000);
         locator.setReconnectAttempts(0);

         locator.setBlockOnNonDurableSend(true);
         locator.setBlockOnDurableSend(true);
         locator.setBlockOnAcknowledge(true);
         locator.setConsumerWindowSize(1024 * 1024);

         ClientSessionFactory sf = locator.createSessionFactory();

         ClientSession session = sf.createSession(false, false, false);

         session.createQueue(PagingTest.ADDRESS, PagingTest.ADDRESS, null, true);

         ClientProducer producer = session.createProducer(PagingTest.ADDRESS);

         byte[] body = new byte[messageSize];

         ByteBuffer bb = ByteBuffer.wrap(body);

         for (int j = 1; j <= messageSize; j++)
         {
            bb.put(getSamplebyte(j));
         }

         for (int i = 0; i < numberOfMessages; i++)
         {
            ClientMessage message = session.createMessage(persistentMessages);

            HornetQBuffer bodyLocal = message.getBodyBuffer();

            bodyLocal.writeBytes(body);

            message.putIntProperty(new SimpleString("id"), i);

            producer.send(message);
            if (i % 1000 == 0)
            {
               session.commit();
            }
         }

         session.commit();

         session.close();

         session = sf.createSession(true, true, 0);

         session.start();

         ClientConsumer consumer = session.createConsumer(ADDRESS);

         for (int i = 0; i < numberOfMessages / 2; i++)
         {
            ClientMessage message = consumer.receive(5000);
            assertNotNull(message);
            assertEquals(i, message.getIntProperty("id").intValue());

            if (i < 100)
            {
               // Do not consume the last one so we could restart
               message.acknowledge();
            }
         }

         session.commit();

         for (ServerSession sessionServer : server.getSessions())
         {
            sessionServer.close(true);
         }

         OperationContextImpl.getContext().waitCompletion();

         ((ClientSessionFactoryImpl)sf).stopPingingAfterOne();

         sf = locator.createSessionFactory();

         session = sf.createSession(true, true, 0);

         session.start();

         consumer = session.createConsumer(ADDRESS);

         for (int i = 100; i < numberOfMessages; i++)
         {
            ClientMessage message = consumer.receive(5000);
            assertNotNull(message);
            assertEquals(i, message.getIntProperty("id").intValue());
            message.acknowledge();
         }

      }
      catch (Throwable e)
      {
         e.printStackTrace();
         throw e;
      }
      finally
      {
         try
         {
            server.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   public void testPageCounter() throws Throwable
   {
      boolean persistentMessages = true;

      Configuration config = createDefaultConfig();

      config.setJournalSyncNonTransactional(false);

      HornetQServer server = createServer(true, config, PAGE_SIZE, PAGE_MAX, new HashMap<String, AddressSettings>());

      server.start();

      final int messageSize = 1024;

      final int numberOfMessages = 500;

      try
      {
         ServerLocator locator = createInVMNonHALocator();

         locator.setClientFailureCheckPeriod(1000);
         locator.setConnectionTTL(2000);
         locator.setReconnectAttempts(0);

         locator.setBlockOnNonDurableSend(true);
         locator.setBlockOnDurableSend(true);
         locator.setBlockOnAcknowledge(true);
         locator.setConsumerWindowSize(1024 * 1024);

         ClientSessionFactory sf = locator.createSessionFactory();

         ClientSession session = sf.createSession(false, false, false);

         Queue q1 = server.createQueue(ADDRESS, ADDRESS, null, true, false);

         Queue q2 = server.createQueue(ADDRESS, new SimpleString("inactive"), null, true, false);

         ClientProducer producer = session.createProducer(PagingTest.ADDRESS);

         byte[] body = new byte[messageSize];

         ByteBuffer bb = ByteBuffer.wrap(body);

         for (int j = 1; j <= messageSize; j++)
         {
            bb.put(getSamplebyte(j));
         }

         final AtomicInteger errors = new AtomicInteger(0);

         Thread t1 = new Thread()
         {
            public void run()
            {
               try
               {
                  ServerLocator sl = createInVMNonHALocator();
                  ClientSessionFactory sf = sl.createSessionFactory();
                  ClientSession sess = sf.createSession(true, true, 0);
                  sess.start();
                  ClientConsumer cons = sess.createConsumer(ADDRESS);
                  for (int i = 0; i < numberOfMessages; i++)
                  {
                     ClientMessage msg = cons.receive(5000);
                     assertNotNull(msg);
                     assertEquals(i, msg.getIntProperty("id").intValue());
                     msg.acknowledge();
                  }
                  
                  assertNull(cons.receiveImmediate());
                  sess.close();
                  sl.close();
               }
               catch (Throwable e)
               {
                  e.printStackTrace();
                  errors.incrementAndGet();
               }

            }
         };

         t1.start();

         for (int i = 0; i < numberOfMessages; i++)
         {
            ClientMessage message = session.createMessage(persistentMessages);

            HornetQBuffer bodyLocal = message.getBodyBuffer();

            bodyLocal.writeBytes(body);

            message.putIntProperty(new SimpleString("id"), i);

            producer.send(message);
            if (i % 20 == 0)
            {
               session.commit();
            }
         }

         session.commit();

         t1.join();

         assertEquals(0, errors.get());

         assertEquals(numberOfMessages, q2.getMessageCount());
         assertEquals(0, q1.getMessageCount());
         
         
         session.close();
         sf.close();
         locator.close();
         
         server.stop();
         
         
         server.start();
         
         Bindings bindings = server.getPostOffice().getBindingsForAddress(ADDRESS);
         
         q1 = null;
         q2 = null;
         
         for (Binding bind : bindings.getBindings())
         {
            if (bind instanceof LocalQueueBinding)
            {
               LocalQueueBinding qb = (LocalQueueBinding)bind;
               if (qb.getQueue().getName().equals(ADDRESS))
               {
                  q1 = qb.getQueue();
               }
               
               if (qb.getQueue().getName().equals(new SimpleString("inactive")))
               {
                  q2 = qb.getQueue();
               }
            }
         }
         
         assertNotNull(q1);
         
         assertNotNull(q2);
         

         assertEquals(numberOfMessages, q2.getMessageCount());
         assertEquals(0, q1.getMessageCount());
         
         
         
         

      }
      catch (Throwable e)
      {
         e.printStackTrace();
         throw e;
      }
      finally
      {
         try
         {
            server.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   public void testPageCounter2() throws Throwable
   {
      boolean persistentMessages = true;

      Configuration config = createDefaultConfig();

      config.setJournalSyncNonTransactional(false);

      HornetQServer server = createServer(true, config, PAGE_SIZE, PAGE_MAX, new HashMap<String, AddressSettings>());

      server.start();

      final int messageSize = 1024;

      final int numberOfMessages = 500;

      try
      {
         ServerLocator locator = createInVMNonHALocator();

         locator.setClientFailureCheckPeriod(1000);
         locator.setConnectionTTL(2000);
         locator.setReconnectAttempts(0);

         locator.setBlockOnNonDurableSend(true);
         locator.setBlockOnDurableSend(true);
         locator.setBlockOnAcknowledge(true);
         locator.setConsumerWindowSize(1024 * 1024);

         ClientSessionFactory sf = locator.createSessionFactory();

         ClientSession session = sf.createSession(false, false, false);

         Queue q1 = server.createQueue(ADDRESS, ADDRESS, null, true, false);

         Queue q2 = server.createQueue(ADDRESS, new SimpleString("inactive"), null, true, false);

         ClientProducer producer = session.createProducer(PagingTest.ADDRESS);

         byte[] body = new byte[messageSize];

         ByteBuffer bb = ByteBuffer.wrap(body);

         for (int j = 1; j <= messageSize; j++)
         {
            bb.put(getSamplebyte(j));
         }

         final AtomicInteger errors = new AtomicInteger(0);

         Thread t1 = new Thread()
         {
            public void run()
            {
               try
               {
                  ServerLocator sl = createInVMNonHALocator();
                  ClientSessionFactory sf = sl.createSessionFactory();
                  ClientSession sess = sf.createSession(true, true, 0);
                  sess.start();
                  ClientConsumer cons = sess.createConsumer(ADDRESS);
                  for (int i = 0; i < 10; i++)
                  {
                     ClientMessage msg = cons.receive(5000);
                     assertNotNull(msg);
                     assertEquals(i, msg.getIntProperty("id").intValue());
                     msg.acknowledge();
                  }
                  sess.close();
                  sl.close();
               }
               catch (Throwable e)
               {
                  e.printStackTrace();
                  errors.incrementAndGet();
               }

            }
         };

         for (int i = 0; i < numberOfMessages; i++)
         {
            ClientMessage message = session.createMessage(persistentMessages);

            HornetQBuffer bodyLocal = message.getBodyBuffer();

            bodyLocal.writeBytes(body);

            message.putIntProperty(new SimpleString("id"), i);

            producer.send(message);
            if (i % 20 == 0)
            {
               session.commit();
            }
         }

         session.commit();

         t1.start();
         t1.join();

         assertEquals(0, errors.get());

         assertEquals(numberOfMessages, q2.getMessageCount());
         assertEquals(numberOfMessages - 10, q1.getMessageCount());

      }
      catch (Throwable e)
      {
         e.printStackTrace();
         throw e;
      }
      finally
      {
         try
         {
            server.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   public void testOrderOverRollback() throws Throwable
   {
      boolean persistentMessages = true;

      Configuration config = createDefaultConfig();

      config.setJournalSyncNonTransactional(false);

      HornetQServer server = createServer(true, config, PAGE_SIZE, PAGE_MAX, new HashMap<String, AddressSettings>());

      server.start();

      final int messageSize = 1024;

      final int numberOfMessages = 3000;

      try
      {
         ServerLocator locator = createInVMNonHALocator();

         locator.setClientFailureCheckPeriod(1000);
         locator.setConnectionTTL(2000);
         locator.setReconnectAttempts(0);

         locator.setBlockOnNonDurableSend(true);
         locator.setBlockOnDurableSend(true);
         locator.setBlockOnAcknowledge(true);
         locator.setConsumerWindowSize(1024 * 1024);

         ClientSessionFactory sf = locator.createSessionFactory();

         ClientSession session = sf.createSession(false, false, false);

         QueueImpl queue = (QueueImpl)server.createQueue(ADDRESS, ADDRESS, null, true, false);

         ClientProducer producer = session.createProducer(PagingTest.ADDRESS);

         byte[] body = new byte[messageSize];

         ByteBuffer bb = ByteBuffer.wrap(body);

         for (int j = 1; j <= messageSize; j++)
         {
            bb.put(getSamplebyte(j));
         }

         for (int i = 0; i < numberOfMessages; i++)
         {
            ClientMessage message = session.createMessage(persistentMessages);

            HornetQBuffer bodyLocal = message.getBodyBuffer();

            bodyLocal.writeBytes(body);

            message.putIntProperty(new SimpleString("id"), i);

            producer.send(message);
            if (i % 1000 == 0)
            {
               session.commit();
            }
         }

         session.commit();

         session.close();

         session = sf.createSession(false, false, 0);

         session.start();

         ClientConsumer consumer = session.createConsumer(ADDRESS);

         for (int i = 0; i < numberOfMessages / 2; i++)
         {
            ClientMessage message = consumer.receive(5000);
            assertNotNull(message);
            assertEquals(i, message.getIntProperty("id").intValue());
            message.acknowledge();
         }

         session.rollback();

         session.close();

         session = sf.createSession(false, false, 0);

         session.start();

         consumer = session.createConsumer(ADDRESS);

         for (int i = 0; i < numberOfMessages; i++)
         {
            ClientMessage message = consumer.receive(5000);
            assertNotNull(message);
            assertEquals(i, message.getIntProperty("id").intValue());
            message.acknowledge();
         }

         session.commit();

      }
      catch (Throwable e)
      {
         e.printStackTrace();
         throw e;
      }
      finally
      {
         try
         {
            server.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   public void testOrderOverRollback2() throws Throwable
   {
      boolean persistentMessages = true;

      Configuration config = createDefaultConfig();

      config.setJournalSyncNonTransactional(false);

      HornetQServer server = createServer(true, config, PAGE_SIZE, PAGE_MAX, new HashMap<String, AddressSettings>());

      server.start();

      final int messageSize = 1024;

      final int numberOfMessages = 200;

      try
      {
         ServerLocator locator = createInVMNonHALocator();

         locator.setClientFailureCheckPeriod(1000);
         locator.setConnectionTTL(2000);
         locator.setReconnectAttempts(0);

         locator.setBlockOnNonDurableSend(true);
         locator.setBlockOnDurableSend(true);
         locator.setBlockOnAcknowledge(true);
         locator.setConsumerWindowSize(0);

         ClientSessionFactory sf = locator.createSessionFactory();

         ClientSession session = sf.createSession(false, false, false);

         QueueImpl queue = (QueueImpl)server.createQueue(ADDRESS, ADDRESS, null, true, false);

         ClientProducer producer = session.createProducer(PagingTest.ADDRESS);

         byte[] body = new byte[messageSize];

         ByteBuffer bb = ByteBuffer.wrap(body);

         for (int j = 1; j <= messageSize; j++)
         {
            bb.put(getSamplebyte(j));
         }

         for (int i = 0; i < numberOfMessages; i++)
         {
            ClientMessage message = session.createMessage(persistentMessages);

            HornetQBuffer bodyLocal = message.getBodyBuffer();

            bodyLocal.writeBytes(body);

            message.putIntProperty(new SimpleString("id"), i);

            producer.send(message);
            if (i % 1000 == 0)
            {
               session.commit();
            }
         }

         session.commit();

         session.close();

         session = sf.createSession(false, false, 0);

         session.start();

         ClientConsumer consumer = session.createConsumer(ADDRESS);

         // number of references without paging
         int numberOfRefs = queue.getNumberOfReferences();

         // consume all non-paged references
         for (int ref = 0; ref < numberOfRefs; ref++)
         {
            ClientMessage msg = consumer.receive(5000);
            assertNotNull(msg);
            msg.acknowledge();
         }

         session.commit();

         session.close();

         session = sf.createSession(false, false, 0);

         session.start();

         consumer = session.createConsumer(ADDRESS);

         ClientMessage msg = consumer.receive(5000);
         assertNotNull(msg);
         int msgIDRolledBack = msg.getIntProperty("id").intValue();
         msg.acknowledge();

         session.rollback();

         msg = consumer.receive(5000);

         assertNotNull(msg);

         assertEquals(msgIDRolledBack, msg.getIntProperty("id").intValue());

         session.rollback();

         session.close();

         sf.close();
         locator.close();

         server.stop();

         server.start();

         locator = createInVMNonHALocator();

         locator.setClientFailureCheckPeriod(1000);
         locator.setConnectionTTL(2000);
         locator.setReconnectAttempts(0);

         locator.setBlockOnNonDurableSend(true);
         locator.setBlockOnDurableSend(true);
         locator.setBlockOnAcknowledge(true);
         locator.setConsumerWindowSize(0);

         sf = locator.createSessionFactory();

         session = sf.createSession(false, false, 0);

         session.start();

         consumer = session.createConsumer(ADDRESS);

         for (int i = msgIDRolledBack; i < numberOfMessages; i++)
         {
            ClientMessage message = consumer.receive(5000);
            assertNotNull(message);
            assertEquals(i, message.getIntProperty("id").intValue());
            message.acknowledge();
         }

         session.commit();

         session.close();

         locator.close();

      }
      catch (Throwable e)
      {
         e.printStackTrace();
         throw e;
      }
      finally
      {
         try
         {
            server.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }
   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
