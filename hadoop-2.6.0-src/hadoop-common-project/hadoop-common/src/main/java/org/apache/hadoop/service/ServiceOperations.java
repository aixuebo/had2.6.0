/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.service;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Evolving;

/**
 * This class contains a set of methods to work with services, especially
 * to walk them through their lifecycle.
 * 该方法是门面方法,属于对服务的抽象,即该方法可以操作任意服务对象
 * 
 */
@Public
@Evolving
public final class ServiceOperations {
  private static final Log LOG = LogFactory.getLog(AbstractService.class);

  private ServiceOperations() {
  }

  /**
   * Stop a service.
   * <p/>Do nothing if the service is null or not
   * in a state in which it can be/needs to be stopped.
   * <p/>
   * The service state is checked <i>before</i> the operation begins.
   * This process is <i>not</i> thread safe.
   * @param service a service or null
   * 停止一个服务
   */
  public static void stop(Service service) {
    if (service != null) {
      service.stop();
    }
  }

  /**
   * Stop a service; if it is null do nothing. Exceptions are caught and
   * logged at warn level. (but not Throwables). This operation is intended to
   * be used in cleanup operations
   *
   * @param service a service; may be null
   * @return any exception that was caught; null if none was.
   * 安静的停止该服务,即停止一个服务,并且有异常的时候将异常日志打印,同时抛异常
   */
  public static Exception stopQuietly(Service service) {
    return stopQuietly(LOG, service);
  }

  /**
   * Stop a service; if it is null do nothing. Exceptions are caught and
   * logged at warn level. (but not Throwables). This operation is intended to
   * be used in cleanup operations
   *
   * @param log the log to warn at
   * @param service a service; may be null
   * @return any exception that was caught; null if none was.
   * @see ServiceOperations#stopQuietly(Service)
   * 安静的停止该服务,即停止一个服务,并且有异常的时候将异常日志打印,同时抛异常
   */
  public static Exception stopQuietly(Log log, Service service) {
    try {
      stop(service);
    } catch (Exception e) {
      log.warn("When stopping the service " + service.getName()
               + " : " + e,
               e);
      return e;
    }
    return null;
  }


  /**
   * Class to manage a list of {@link ServiceStateChangeListener} instances,
   * including a notification loop that is robust against changes to the list
   * during the notification process.
   * 每一个服务持有一个该对象,当状态发生变化的时候,通知所有监听该服务的对象,告诉他们我发生变化了
   */
  public static class ServiceListeners {
    /**
     * List of state change listeners; it is final to guarantee
     * that it will never be null.
     * 当服务更改的时候,通知哪些ServiceStateChangeListener
     */
    private final List<ServiceStateChangeListener> listeners =
      new ArrayList<ServiceStateChangeListener>();

    /**
     * Thread-safe addition of a new listener to the end of a list.
     * Attempts to re-register a listener that is already registered
     * will be ignored.
     * @param l listener
     * 添加一个服务更改监听者
     */
    public synchronized void add(ServiceStateChangeListener l) {
      if(!listeners.contains(l)) {
        listeners.add(l);
      }
    }

    /**
     * Remove any registration of a listener from the listener list.
     * @param l listener
     * @return true if the listener was found (and then removed)
     */
    public synchronized boolean remove(ServiceStateChangeListener l) {
      return listeners.remove(l);
    }

    /**
     * Reset the listener list
     */
    public synchronized void reset() {
      listeners.clear();
    }

    /**
     * Change to a new state and notify all listeners.
     * This method will block until all notifications have been issued.
     * It caches the list of listeners before the notification begins,
     * so additions or removal of listeners will not be visible.
     * @param service the service that has changed state
     * 如果Service服务状态更改了,通知所有他的监听者
     */
    public void notifyListeners(Service service) {
      //take a very fast snapshot of the callback list
      //very much like CopyOnWriteArrayList, only more minimal
      ServiceStateChangeListener[] callbacks;
      synchronized (this) {
        callbacks = listeners.toArray(new ServiceStateChangeListener[listeners.size()]);
      }
      //iterate through the listeners outside the synchronized method,
      //ensuring that listener registration/unregistration doesn't break anything
      for (ServiceStateChangeListener l : callbacks) {
        l.stateChanged(service);
      }
    }
  }

}
