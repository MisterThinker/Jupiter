/*
 * Copyright (c) 2015 The Jupiter Project
 *
 * Licensed under the Apache License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jupiter.rpc.consumer;

import org.jupiter.common.util.*;
import org.jupiter.rpc.*;
import org.jupiter.rpc.consumer.cluster.ClusterInvoker;
import org.jupiter.rpc.consumer.cluster.FailFastClusterInvoker;
import org.jupiter.rpc.consumer.cluster.FailOverClusterInvoker;
import org.jupiter.rpc.consumer.cluster.FailSafeClusterInvoker;
import org.jupiter.rpc.consumer.dispatcher.DefaultBroadcastDispatcher;
import org.jupiter.rpc.consumer.dispatcher.DefaultRoundDispatcher;
import org.jupiter.rpc.consumer.dispatcher.Dispatcher;
import org.jupiter.rpc.consumer.invoker.AsyncInvoker;
import org.jupiter.rpc.consumer.invoker.SyncInvoker;
import org.jupiter.rpc.load.balance.LoadBalancerFactory;
import org.jupiter.rpc.load.balance.LoadBalancerType;
import org.jupiter.rpc.model.metadata.ServiceMetadata;
import org.jupiter.serialization.SerializerType;
import org.jupiter.transport.JConnection;
import org.jupiter.transport.JConnector;
import org.jupiter.transport.UnresolvedAddress;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.jupiter.common.util.Preconditions.checkNotNull;
import static org.jupiter.rpc.DispatchType.BROADCAST;
import static org.jupiter.rpc.DispatchType.ROUND;
import static org.jupiter.rpc.InvokeType.ASYNC;
import static org.jupiter.rpc.InvokeType.SYNC;
import static org.jupiter.rpc.load.balance.LoadBalancerType.RANDOM;
import static org.jupiter.serialization.SerializerType.PROTO_STUFF;

/**
 * Proxy factory
 *
 * Consumer对象代理工厂
 *
 * jupiter
 * org.jupiter.rpc.consumer
 *
 * @author jiachun.fjc
 */
public class ProxyFactory<I> {

    // 接口类型
    private final Class<I> interfaceClass;
    // 服务版本号, 通常在接口不兼容时版本号才需要升级
    private String version;

    // jupiter client
    private JClient client;
    // 序列化/反序列化方式
    private SerializerType serializerType = PROTO_STUFF;
    // 软负载均衡类型
    private LoadBalancerType loadBalancerType = RANDOM;
    // provider地址
    private List<UnresolvedAddress> addresses;
    // 调用方式 [同步; 异步]
    private InvokeType invokeType = SYNC;
    // 派发方式 [单播; 组播]
    private DispatchType dispatchType = ROUND;
    // 调用超时时间设置
    private long timeoutMillis;
    // 指定方法单独设置的超时时间, 方法名为key, 方法参数类型不做区别对待
    private Map<String, Long> methodsSpecialTimeoutMillis;
    // 消费者端钩子函数
    private List<ConsumerHook> hooks;
    // 集群容错策略
    private ClusterInvoker.Strategy strategy = ClusterInvoker.Strategy.FAIL_FAST;
    // failover重试次数
    private int retries = 2;

    public static <I> ProxyFactory<I> factory(Class<I> interfaceClass) {
        ProxyFactory<I> factory = new ProxyFactory<>(interfaceClass);
        // 初始化数据
        factory.addresses = Lists.newArrayList();
        factory.hooks = Lists.newArrayList();
        factory.methodsSpecialTimeoutMillis = Maps.newHashMap();

        return factory;
    }

    private ProxyFactory(Class<I> interfaceClass) {
        this.interfaceClass = interfaceClass;
    }

    public Class<I> getInterfaceClass() {
        return interfaceClass;
    }

    /**
     * Sets the version.
     */
    public ProxyFactory<I> version(String version) {
        this.version = version;
        return this;
    }

    /**
     * Sets the jupiter client.
     */
    public ProxyFactory<I> client(JClient client) {
        this.client = client;
        return this;
    }

    /**
     * Sets the service serializer type.
     */
    public ProxyFactory<I> serializerType(SerializerType serializerType) {
        this.serializerType = serializerType;
        return this;
    }

    /**
     * Sets the service load balancer type.
     */
    public ProxyFactory<I> loadBalancerType(LoadBalancerType loadBalancerType) {
        this.loadBalancerType = loadBalancerType;
        return this;
    }

    /**
     * Adds provider's addresses.
     */
    public ProxyFactory<I> addProviderAddress(UnresolvedAddress... addresses) {
        Collections.addAll(this.addresses, addresses);
        return this;
    }

    /**
     * Adds provider's addresses.
     */
    public ProxyFactory<I> addProviderAddress(List<UnresolvedAddress> addresses) {
        this.addresses.addAll(addresses);
        return this;
    }

    /**
     * Synchronous blocking, asynchronous with future or asynchronous with callback,
     * the default is synchronous.
     */
    public ProxyFactory<I> invokeType(InvokeType invokeType) {
        this.invokeType = checkNotNull(invokeType);
        return this;
    }

    /**
     * Sets the type of dispatch, the default is {@link DispatchType#ROUND}
     */
    public ProxyFactory<I> dispatchType(DispatchType dispatchType) {
        this.dispatchType = checkNotNull(dispatchType);
        return this;
    }

    /**
     * Timeout milliseconds.
     */
    public ProxyFactory<I> timeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    /**
     * Method special timeout milliseconds.
     */
    public ProxyFactory<I> methodSpecialTimeoutMillis(String methodName, long timeoutMillis) {
        methodsSpecialTimeoutMillis.put(methodName, timeoutMillis);
        return this;
    }

    /**
     * Adds hooks.
     */
    public ProxyFactory<I> addHook(ConsumerHook... hooks) {
        Collections.addAll(this.hooks, hooks);
        return this;
    }

    /**
     * Sets cluster strategy, only support ROUND & SYNC mode.
     */
    public ProxyFactory<I> clusterStrategy(ClusterInvoker.Strategy strategy) {
        this.strategy = strategy;
        return this;
    }

    /**
     * Sets failover strategy's retries.
     */
    public ProxyFactory<I> failoverRetries(int retries) {
        this.retries = retries;
        return this;
    }

    public I newProxyInstance() {
        // check arguments
        checkNotNull(client, "client");
        checkNotNull(interfaceClass, "interfaceClass");
        checkNotNull(serializerType, "serializerType");

        if (dispatchType == BROADCAST && invokeType != ASYNC) {
            throw new UnsupportedOperationException("illegal type, BROADCAST only support ASYNC");
        }
        ServiceProvider annotation = interfaceClass.getAnnotation(ServiceProvider.class);

        checkNotNull(annotation, interfaceClass + " is not a ServiceProvider interface");

        String providerName = annotation.name();
        providerName = Strings.isNotBlank(providerName) ? providerName : interfaceClass.getName();
        String version = Strings.isNotBlank(this.version) ? this.version : JConstants.DEFAULT_VERSION;

        // metadata
        ServiceMetadata metadata = new ServiceMetadata(annotation.group(), version, providerName);

        JConnector<JConnection> connector = client.connector();
        for (UnresolvedAddress address : addresses) {
            connector.addChannelGroup(metadata, connector.group(address));
        }

        // dispatcher
        Dispatcher dispatcher = dispatcher(metadata, serializerType)
                .hooks(hooks)
                .timeoutMillis(timeoutMillis)
                .methodsSpecialTimeoutMillis(methodsSpecialTimeoutMillis);

        Object handler;
        switch (invokeType) {
            case SYNC:
                handler = new SyncInvoker(clusterInvoker(strategy, dispatcher));
                break;
            case ASYNC:
                handler = new AsyncInvoker(clusterInvoker(strategy, dispatcher));
                break;
            default:
                throw new IllegalStateException("InvokeType: " + invokeType);
        }

        return Proxies.getDefault().newProxy(interfaceClass, handler);
    }

    protected Dispatcher dispatcher(ServiceMetadata metadata, SerializerType serializerType) {
        switch (dispatchType) {
            case ROUND:
                return new DefaultRoundDispatcher(
                        LoadBalancerFactory.loadBalancer(loadBalancerType), metadata, serializerType);
            case BROADCAST:
                return new DefaultBroadcastDispatcher(metadata, serializerType);
            default:
                throw new IllegalStateException("DispatchType: " + dispatchType);
        }
    }

    private ClusterInvoker clusterInvoker(ClusterInvoker.Strategy strategy, Dispatcher dispatcher) {
        switch (strategy) {
            case FAIL_FAST:
                return new FailFastClusterInvoker(client, dispatcher);
            case FAIL_OVER:
                return new FailOverClusterInvoker(client, dispatcher, retries);
            case FAIL_SAFE:
                return new FailSafeClusterInvoker(client, dispatcher);
            default:
                throw new IllegalStateException("Strategy: " + strategy);
        }
    }
}
