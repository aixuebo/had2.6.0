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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.apache.hadoop.conf.Configuration;

import com.google.common.annotations.VisibleForTesting;

/**
 * This is the base implementation class for services.
 */
@Public
@Evolving
public abstract class AbstractService implements Service {

  private static final Log LOG = LogFactory.getLog(AbstractService.class);

  /**
   * Service name.
   * 每一个服务对应一个名称
   */
  private final String name;

  /** service state 
   * 每一个服务对应一个状态对象
   **/
  private final ServiceStateModel stateModel;

  /**
   * Service start time. Will be zero until the service is started.
   * 服务的开启时间
   */
  private long startTime;

  /**
   * The configuration. Will be null until the service is initialized.
   */
  private volatile Configuration config;

  /**
   * List of state change listeners; it is final to ensure
   * that it will never be null.
   * 每一个服务持有一个该对象,当状态发生变化的时候,通知所有监听该服务的对象,告诉他们我发生变化了
   */
  private final ServiceOperations.ServiceListeners listeners
    = new ServiceOperations.ServiceListeners();
  
  /**
   * Static listeners to all events across all services
   * 静态的,监听全局信息
   */
  private static ServiceOperations.ServiceListeners globalListeners
    = new ServiceOperations.ServiceListeners();

  /**
   * The cause of any failure -will be null.
   * if a service did not stop due to a failure.
   * 该服务失败的时候的异常
   */
  private Exception failureCause;

  /**
   * the state in which the service was when it failed.
   * Only valid when the service is stopped due to a failure
   * 当失败的时候,该服务的状态
   */
  private STATE failureState = null;

  /**
   * object used to co-ordinate {@link #waitForServiceToStop(long)}
   * across threads.
   * true表示该服务已经终止stop了
   */
  private final AtomicBoolean terminationNotification =
    new AtomicBoolean(false);

  /**
   * History of lifecycle transitions
   * 集合的每一个对象表示:记录日志,记录什么时间点,当前服务的状态是什么
   */
  private final List<LifecycleEvent> lifecycleHistory
    = new ArrayList<LifecycleEvent>(5);

  /**
   * Map of blocking dependencies
   */
  private final Map<String,String> blockerMap = new HashMap<String, String>();

  private final Object stateChangeLock = new Object();//服务状态变更时候的锁对象
 
  /**
   * Construct the service.
   * @param name service name
   */
  public AbstractService(String name) {
    this.name = name;
    stateModel = new ServiceStateModel(name);
  }

  @Override
  public final STATE getServiceState() {
    return stateModel.getState();
  }

  @Override
  public final synchronized Throwable getFailureCause() {
    return failureCause;
  }

  @Override
  public synchronized STATE getFailureState() {
    return failureState;
  }

  /**
   * Set the configuration for this service.
   * This method is called during {@link #init(Configuration)}
   * and should only be needed if for some reason a service implementation
   * needs to override that initial setting -for example replacing
   * it with a new subclass of {@link Configuration}
   * @param conf new configuration.
   */
  protected void setConfig(Configuration conf) {
    this.config = conf;
  }

  /**
   * {@inheritDoc}
   * This invokes {@link #serviceInit}
   * @param conf the configuration of the service. This must not be null
   * @throws ServiceStateException if the configuration was null,
   * the state change not permitted, or something else went wrong
   */
  @Override
  public void init(Configuration conf) {
    if (conf == null) {
      throw new ServiceStateException("Cannot initialize service "
                                      + getName() + ": null configuration");
    }
    if (isInState(STATE.INITED)) {//如果状态是初始化完成了,则直接返回
      return;
    }
    synchronized (stateChangeLock) {
      if (enterState(STATE.INITED) != STATE.INITED) {//说明切换到INITED状态,并且老的状态不是INITED
        setConfig(conf);
        try {
          serviceInit(config);
          if (isInState(STATE.INITED)) {//确保初始化完成
            //if the service ended up here during init,
            //notify the listeners
            notifyListeners();//通知服务的状态变更了
          }
        } catch (Exception e) {
          noteFailure(e);//初始化失败
          ServiceOperations.stopQuietly(LOG, this);//关闭该服务
          throw ServiceStateException.convert(e);
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   * @throws ServiceStateException if the current service state does not permit
   * this action
   */
  @Override
  public void start() {
    if (isInState(STATE.STARTED)) {//如果该服务状态已经开始了,则停止
      return;
    }
    //enter the started state
    synchronized (stateChangeLock) {
      if (stateModel.enterState(STATE.STARTED) != STATE.STARTED) {//说明切换到STARTED状态,并且老的状态不是STARTED
        try {
          startTime = System.currentTimeMillis();
          serviceStart();
          if (isInState(STATE.STARTED)) {
            //if the service started (and isn't now in a later state), notify
            if (LOG.isDebugEnabled()) {
              LOG.debug("Service " + getName() + " is started");
            }
            notifyListeners();//通知服务的状态变更了
          }
        } catch (Exception e) {
          noteFailure(e);
          ServiceOperations.stopQuietly(LOG, this);
          throw ServiceStateException.convert(e);
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {
    if (isInState(STATE.STOPPED)) {
      return;
    }
    synchronized (stateChangeLock) {
      if (enterState(STATE.STOPPED) != STATE.STOPPED) {
        try {
          serviceStop();
        } catch (Exception e) {
          //stop-time exceptions are logged if they are the first one,
          noteFailure(e);
          throw ServiceStateException.convert(e);
        } finally {
          //report that the service has terminated
          terminationNotification.set(true);
          synchronized (terminationNotification) {
            terminationNotification.notifyAll();
          }
          //notify anything listening for events
          notifyListeners();
        }
      } else {
        //already stopped: note it
        if (LOG.isDebugEnabled()) {
          LOG.debug("Ignoring re-entrant call to stop()");
        }
      }
    }
  }

  /**
   * Relay to {@link #stop()}
   * @throws IOException
   */
  @Override
  public final void close() throws IOException {
    stop();
  }

  /**
   * Failure handling: record the exception
   * that triggered it -if there was not one already.
   * Services are free to call this themselves.
   * @param exception the exception
   */
  protected final void noteFailure(Exception exception) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("noteFailure " + exception, null);
    }
    if (exception == null) {
      //make sure failure logic doesn't itself cause problems
      return;
    }
    //record the failure details, and log it
    synchronized (this) {
      if (failureCause == null) {
        failureCause = exception;
        failureState = getServiceState();
        LOG.info("Service " + getName()
                 + " failed in state " + failureState
                 + "; cause: " + exception,
                 exception);
      }
    }
  }

  /**
   * 如果服务没有被stop,则先等待timeout毫秒
   * 返回值true,表示服务已经stop完了,false表示经过timeout毫秒后,服务依然没有stop
   */
  @Override
  public final boolean waitForServiceToStop(long timeout) {
    boolean completed = terminationNotification.get();//true表示该服务已经stop了
    while (!completed) {//false说明该服务尚未被stop
      try {
        synchronized(terminationNotification) {//等待若干毫秒
          terminationNotification.wait(timeout);
        }
        // here there has been a timeout, the object has terminated,
        // or there has been a spurious wakeup (which we ignore)
        //睡眠后,直接退出循环,再次获取服务是否完成了stop方法
        completed = true;
      } catch (InterruptedException e) {
        // interrupted; have another look at the flag
        completed = terminationNotification.get();
      }
    }
    return terminationNotification.get();
  }

  /* ===================================================================== */
  /* Override Points */
  /* ===================================================================== */

  /**
   * All initialization code needed by a service.
   *
   * This method will only ever be called once during the lifecycle of
   * a specific service instance.
   *
   * Implementations do not need to be synchronized as the logic
   * in {@link #init(Configuration)} prevents re-entrancy.
   *
   * The base implementation checks to see if the subclass has created
   * a new configuration instance, and if so, updates the base class value
   * @param conf configuration
   * @throws Exception on a failure -these will be caught,
   * possibly wrapped, and wil; trigger a service stop
   * 初始化服务
   */
  protected void serviceInit(Configuration conf) throws Exception {
    if (conf != config) {
      LOG.debug("Config has been overridden during init");
      setConfig(conf);
    }
  }

  /**
   * Actions called during the INITED to STARTED transition.
   *
   * This method will only ever be called once during the lifecycle of
   * a specific service instance.
   *
   * Implementations do not need to be synchronized as the logic
   * in {@link #start()} prevents re-entrancy.
   *
   * @throws Exception if needed -these will be caught,
   * wrapped, and trigger a service stop
   * 开启服务
   */
  protected void serviceStart() throws Exception {

  }

  /**
   * Actions called during the transition to the STOPPED state.
   *
   * This method will only ever be called once during the lifecycle of
   * a specific service instance.
   *
   * Implementations do not need to be synchronized as the logic
   * in {@link #stop()} prevents re-entrancy.
   *
   * Implementations MUST write this to be robust against failures, including
   * checks for null references -and for the first failure to not stop other
   * attempts to shut down parts of the service.
   *
   * @throws Exception if needed -these will be caught and logged.
   * 停止服务
   */
  protected void serviceStop() throws Exception {

  }

  /**
   * 为该服务注册监听
   */
  @Override
  public void registerServiceListener(ServiceStateChangeListener l) {
    listeners.add(l);
  }

  @Override
  public void unregisterServiceListener(ServiceStateChangeListener l) {
    listeners.remove(l);
  }

  /**
   * Register a global listener, which receives notifications
   * from the state change events of all services in the JVM
   * @param l listener
   */
  public static void registerGlobalListener(ServiceStateChangeListener l) {
    globalListeners.add(l);
  }

  /**
   * unregister a global listener.
   * @param l listener to unregister
   * @return true if the listener was found (and then deleted)
   */
  public static boolean unregisterGlobalListener(ServiceStateChangeListener l) {
    return globalListeners.remove(l);
  }

  /**
   * Package-scoped method for testing -resets the global listener list
   */
  @VisibleForTesting
  static void resetGlobalListeners() {
    globalListeners.reset();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public synchronized Configuration getConfig() {
    return config;
  }

  @Override
  public long getStartTime() {
    return startTime;
  }

  /**
   * Notify local and global listeners of state changes.
   * Exceptions raised by listeners are NOT passed up.
   * 通知该服务的状态变更了
   */
  private void notifyListeners() {
    try {
      listeners.notifyListeners(this);
      globalListeners.notifyListeners(this);
    } catch (Throwable e) {
      LOG.warn("Exception while notifying listeners of " + this + ": " + e,
               e);
    }
  }

  /**
   * Add a state change event to the lifecycle history
   * 记录日志,记录什么时间点,当前服务的状态是什么
   */
  private void recordLifecycleEvent() {
    LifecycleEvent event = new LifecycleEvent();
    event.time = System.currentTimeMillis();
    event.state = getServiceState();
    lifecycleHistory.add(event);
  }

  //复制所有状态变更日志
  @Override
  public synchronized List<LifecycleEvent> getLifecycleHistory() {
    return new ArrayList<LifecycleEvent>(lifecycleHistory);
  }

  /**
   * Enter a state; record this via {@link #recordLifecycleEvent}
   * and log at the info level.
   * @param newState the proposed new state
   * @return the original state
   * it wasn't already in that state, and the state model permits state re-entrancy.
   * 切换状态,将服务的老状态切换成参数新状态,返回值是以前的老状态
   */
  private STATE enterState(STATE newState) {
    assert stateModel != null : "null state in " + name + " " + this.getClass();
    //切换状态,将服务的老状态切换成参数新状态,返回值是以前的老状态
    STATE oldState = stateModel.enterState(newState);
    if (oldState != newState) {
      if (LOG.isDebugEnabled()) {//打印切换后的状态
        LOG.debug(
          "Service: " + getName() + " entered state " + getServiceState());
      }
      recordLifecycleEvent();
    }
    return oldState;
  }

  //判断当前状态是否与目标状态一致
  @Override
  public final boolean isInState(Service.STATE expected) {
    return stateModel.isInState(expected);
  }

  @Override
  public String toString() {
    return "Service " + name + " in state " + stateModel;
  }

  /**
   * Put a blocker to the blocker map -replacing any
   * with the same name.
   * @param name blocker name
   * @param details any specifics on the block. This must be non-null.
   */
  protected void putBlocker(String name, String details) {
    synchronized (blockerMap) {
      blockerMap.put(name, details);
    }
  }

  /**
   * Remove a blocker from the blocker map -
   * this is a no-op if the blocker is not present
   * @param name the name of the blocker
   */
  public void removeBlocker(String name) {
    synchronized (blockerMap) {
      blockerMap.remove(name);
    }
  }

  @Override
  public Map<String, String> getBlockers() {
    synchronized (blockerMap) {
      Map<String, String> map = new HashMap<String, String>(blockerMap);
      return map;
    }
  }
}
