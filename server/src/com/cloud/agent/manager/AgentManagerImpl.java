/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 *
 * This software is licensed under the GNU General Public License v3 or later.
 *
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.cloud.agent.manager;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.StartupCommandProcessor;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckHealthCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.GetHostStatsAnswer;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.MaintainAnswer;
import com.cloud.agent.api.MaintainCommand;
import com.cloud.agent.api.PingAnswer;
import com.cloud.agent.api.PingCommand;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.PoolEjectCommand;
import com.cloud.agent.api.ReadyAnswer;
import com.cloud.agent.api.ReadyCommand;
import com.cloud.agent.api.ShutdownCommand;
import com.cloud.agent.api.StartupAnswer;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupExternalDhcpCommand;
import com.cloud.agent.api.StartupExternalFirewallCommand;
import com.cloud.agent.api.StartupExternalLoadBalancerCommand;
import com.cloud.agent.api.StartupProxyCommand;
import com.cloud.agent.api.StartupPxeServerCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.StartupSecondaryStorageCommand;
import com.cloud.agent.api.StartupStorageCommand;
import com.cloud.agent.api.StartupTrafficMonitorCommand;
import com.cloud.agent.api.UnsupportedAnswer;
import com.cloud.agent.api.UpdateHostPasswordCommand;
import com.cloud.agent.manager.allocator.PodAllocator;
import com.cloud.agent.transport.Request;
import com.cloud.agent.transport.Response;
import com.cloud.alert.AlertManager;
import com.cloud.api.ApiConstants;
import com.cloud.api.commands.UpdateHostPasswordCmd;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityVO;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.cluster.ManagementServerNode;
import com.cloud.cluster.StackMaid;
import com.cloud.configuration.Config;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterIpAddressVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.PodCluster;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterIpAddressDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConnectionException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.UnsupportedVersionException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.ha.HighAvailabilityManager.WorkType;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.Host.HostAllocationState;
import com.cloud.host.Host.Type;
import com.cloud.host.HostStats;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.Status.Event;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.kvm.resource.KvmDummyResourceBase;
import com.cloud.network.IPAddressVO;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.resource.ServerResource;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.StoragePoolVO;
import com.cloud.storage.StorageService;
import com.cloud.storage.dao.StoragePoolDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.resource.DummySecondaryStorageResource;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.ActionDelegate;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.component.Adapters;
import com.cloud.utils.component.ComponentLocator;
import com.cloud.utils.component.Inject;
import com.cloud.utils.component.Manager;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.HypervisorVersionChangedException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.nio.HandlerFactory;
import com.cloud.utils.nio.Link;
import com.cloud.utils.nio.NioServer;
import com.cloud.utils.nio.Task;
import com.cloud.utils.time.InaccurateClock;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;

import edu.emory.mathcs.backport.java.util.Collections;

/**
 * Implementation of the Agent Manager. This class controls the connection to the agents.
 *
 * @config {@table || Param Name | Description | Values | Default || || port | port to listen on for agent connection. | Integer
 *         | 8250 || || workers | # of worker threads | Integer | 5 || || router.template.id | default id for template | Integer
 *         | 1 || || router.ram.size | default ram for router vm in mb | Integer | 128 || || router.ip.address | ip address for
 *         the router | ip | 10.1.1.1 || || wait | Time to wait for control commands to return | seconds | 1800 || || domain |
 *         domain for domain routers| String | foo.com || || alert.wait | time to wait before alerting on a disconnected agent |
 *         seconds | 1800 || || update.wait | time to wait before alerting on a updating agent | seconds | 600 || ||
 *         ping.interval | ping interval in seconds | seconds | 60 || || instance.name | Name of the deployment String |
 *         required || || start.retry | Number of times to retry start | Number | 2 || || ping.timeout | multiplier to
 *         ping.interval before announcing an agent has timed out | float | 2.0x || || router.stats.interval | interval to
 *         report router statistics | seconds | 300s || * }
 **/
@Local(value = { AgentManager.class })
public class AgentManagerImpl implements AgentManager, HandlerFactory, Manager {
    private static final Logger s_logger = Logger.getLogger(AgentManagerImpl.class);

    protected ConcurrentHashMap<Long, AgentAttache> _agents = new ConcurrentHashMap<Long, AgentAttache>(10007);
    protected List<Pair<Integer, Listener>> _hostMonitors = new ArrayList<Pair<Integer, Listener>>(17);
    protected List<Pair<Integer, Listener>> _cmdMonitors = new ArrayList<Pair<Integer, Listener>>(17);
    protected List<Pair<Integer, StartupCommandProcessor>> _creationMonitors = new ArrayList<Pair<Integer, StartupCommandProcessor>>(17);
    protected List<Long> _loadingAgents = new ArrayList<Long>();
    protected int _monitorId = 0;

    protected NioServer _connection;
    @Inject
    protected HostDao _hostDao = null;
    @Inject
    protected HostDetailsDao _detailsDao = null;
    @Inject
    protected DataCenterDao _dcDao = null;
    @Inject
    protected DataCenterIpAddressDao _privateIPAddressDao = null;
    @Inject
    protected IPAddressDao _publicIPAddressDao = null;
    @Inject
    protected HostPodDao _podDao = null;
    @Inject(adapter = PodAllocator.class)
    protected Adapters<PodAllocator> _podAllocators = null;
    @Inject
    protected VMInstanceDao _vmDao = null;
    @Inject
    protected CapacityDao _capacityDao = null;
    @Inject
    protected ConfigurationDao _configDao = null;
    @Inject
    protected StoragePoolDao _storagePoolDao = null;
    @Inject
    protected StoragePoolHostDao _storagePoolHostDao = null;
    @Inject
    protected HostDetailsDao _hostDetailsDao = null;
    @Inject
    protected ClusterDao _clusterDao = null;
    @Inject
    protected ClusterDetailsDao _clusterDetailsDao = null;
    @Inject
    protected HostTagsDao _hostTagsDao = null;
    @Inject
    protected VolumeDao _volumeDao = null;

    protected int _port;

    @Inject
    protected HighAvailabilityManager _haMgr = null;
    @Inject
    protected AlertManager _alertMgr = null;

    @Inject
    protected AccountManager _accountMgr = null;

    @Inject
    protected VirtualMachineManager _vmMgr = null;
    
    @Inject StorageService _storageSvr = null;
    @Inject StorageManager _storageMgr = null;

    protected int _retry = 2;

    protected String _name;
    protected String _instance;

    protected int _wait;
    protected int _updateWait;
    protected int _alertWait;
    protected long _nodeId = -1;

    protected Random _rand = new Random(System.currentTimeMillis());

    protected int _pingInterval;
    protected long _pingTimeout;
    protected AgentMonitor _monitor = null;

    protected ExecutorService _executor;
    
    protected StateMachine2<Status, Status.Event, Host> _statusStateMachine = Status.getStateMachine();
    
    @Inject ResourceManager _resourceMgr;

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _name = name;

        final ComponentLocator locator = ComponentLocator.getCurrentLocator();
        ConfigurationDao configDao = locator.getDao(ConfigurationDao.class);
        if (configDao == null) {
            throw new ConfigurationException("Unable to get the configuration dao.");
        }

        final Map<String, String> configs = configDao.getConfiguration("AgentManager", params);
        _port = NumbersUtil.parseInt(configs.get("port"), 8250);
        final int workers = NumbersUtil.parseInt(configs.get("workers"), 5);

        String value = configs.get(Config.PingInterval.toString());
        _pingInterval = NumbersUtil.parseInt(value, 60);

        value = configs.get(Config.Wait.toString());
        _wait = NumbersUtil.parseInt(value, 1800);

        value = configs.get(Config.AlertWait.toString());
        _alertWait = NumbersUtil.parseInt(value, 1800);

        value = configs.get(Config.UpdateWait.toString());
        _updateWait = NumbersUtil.parseInt(value, 600);

        value = configs.get(Config.PingTimeout.toString());
        final float multiplier = value != null ? Float.parseFloat(value) : 2.5f;
        _pingTimeout = (long) (multiplier * _pingInterval);

        s_logger.info("Ping Timeout is " + _pingTimeout);

        value = configs.get(Config.DirectAgentLoadSize.key());
        int threads = NumbersUtil.parseInt(value, 16);

        _instance = configs.get("instance.name");
        if (_instance == null) {
            _instance = "DEFAULT";
        }

        _nodeId = ManagementServerNode.getManagementServerId();
        s_logger.info("Configuring AgentManagerImpl. management server node id(msid): " + _nodeId);
        
        long lastPing = (System.currentTimeMillis() >> 10) - _pingTimeout;
        _hostDao.markHostsAsDisconnected(_nodeId, lastPing);

        _monitor = ComponentLocator.inject(AgentMonitor.class, _nodeId, _hostDao, _vmDao, _dcDao, _podDao, this, _alertMgr, _pingTimeout);
        registerForHostEvents(_monitor, true, true, false);

        _executor = new ThreadPoolExecutor(threads, threads, 60l, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new NamedThreadFactory("AgentTaskPool"));

        _connection = new NioServer("AgentManager", _port, workers + 10, this);

        s_logger.info("Listening on " + _port + " with " + workers + " workers");
        return true;
    }

    @Override
    public boolean isHostNativeHAEnabled(long hostId) {
        HostVO host = _hostDao.findById(hostId);
        if (host.getClusterId() != null) {
            ClusterDetailsVO detail = _clusterDetailsDao.findDetail(host.getClusterId(), "NativeHA");
            return detail == null ? false : Boolean.parseBoolean(detail.getValue());
        }
        return false;
    }

    @Override
    public Task create(Task.Type type, Link link, byte[] data) {
        return new AgentHandler(type, link, data);
    }

    @Override
    public int registerForHostEvents(final Listener listener, boolean connections, boolean commands, boolean priority) {
        synchronized (_hostMonitors) {
            _monitorId++;
            if (connections) {
                if (priority) {
                    _hostMonitors.add(0, new Pair<Integer, Listener>(_monitorId, listener));
                } else {
                    _hostMonitors.add(new Pair<Integer, Listener>(_monitorId, listener));
                }
            }
            if (commands) {
                if (priority) {
                    _cmdMonitors.add(0, new Pair<Integer, Listener>(_monitorId, listener));
                } else {
                    _cmdMonitors.add(new Pair<Integer, Listener>(_monitorId, listener));
                }
            }
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Registering listener " + listener.getClass().getSimpleName() + " with id " + _monitorId);
            }
            return _monitorId;
        }
    }

    @Override
    public int registerForInitialConnects(final StartupCommandProcessor creator,boolean priority) {
        synchronized (_hostMonitors) {
            _monitorId++;

            if (priority) {
                _creationMonitors.add(0, new Pair<Integer, StartupCommandProcessor>(
                        _monitorId, creator));
            } else {
                _creationMonitors.add(0, new Pair<Integer, StartupCommandProcessor>(
                        _monitorId, creator));
            }
        }

        return _monitorId;
    }

    @Override
    public void unregisterForHostEvents(final int id) {
        s_logger.debug("Deregistering " + id);
        _hostMonitors.remove(id);
    }

    private AgentControlAnswer handleControlCommand(AgentAttache attache, final AgentControlCommand cmd) {
        AgentControlAnswer answer = null;

        for (Pair<Integer, Listener> listener : _cmdMonitors) {
            answer = listener.second().processControlCommand(attache.getId(), cmd);

            if (answer != null) {
                return answer;
            }
        }

        s_logger.warn("No handling of agent control command: " + cmd + " sent from " + attache.getId());
        return new AgentControlAnswer(cmd);
    }

    public void handleCommands(AgentAttache attache, final long sequence, final Command[] cmds) {
        for (Pair<Integer, Listener> listener : _cmdMonitors) {
            boolean processed = listener.second().processCommands(attache.getId(), sequence, cmds);
            if (s_logger.isTraceEnabled()) {
                s_logger.trace("SeqA " + attache.getId() + "-" + sequence + ": " + (processed ? "processed" : "not processed") + " by " + listener.getClass());
            }
        }
    }

    @Override
    public void notifyAnswersToMonitors(long agentId, long seq, Answer[] answers) {
        for (Pair<Integer, Listener> listener : _cmdMonitors) {
            listener.second().processAnswers(agentId, seq, answers);
        }
    }

    @Override
    public AgentAttache findAttache(long hostId) {
        AgentAttache attache = null;
        synchronized (_agents) {
            attache = _agents.get(hostId);
        }
        return attache;
    }

    @Override
    public Set<Long> getConnectedHosts() {
        // make the returning set be safe for concurrent iteration
        final HashSet<Long> result = new HashSet<Long>();

        synchronized (_agents) {
            final Set<Long> s = _agents.keySet();
            for (final Long id : s) {
                result.add(id);
            }
        }
        return result;
    }

    @Override
    public List<PodCluster> listByDataCenter(long dcId) {
        List<HostPodVO> pods = _podDao.listByDataCenterId(dcId);
        ArrayList<PodCluster> pcs = new ArrayList<PodCluster>();
        for (HostPodVO pod : pods) {
            List<ClusterVO> clusters = _clusterDao.listByPodId(pod.getId());
            if (clusters.size() == 0) {
                pcs.add(new PodCluster(pod, null));
            } else {
                for (ClusterVO cluster : clusters) {
                    pcs.add(new PodCluster(pod, cluster));
                }
            }
        }
        return pcs;
    }

    @Override
    public List<PodCluster> listByPod(long podId) {
        ArrayList<PodCluster> pcs = new ArrayList<PodCluster>();
        HostPodVO pod = _podDao.findById(podId);
        if (pod == null) {
            return pcs;
        }
        List<ClusterVO> clusters = _clusterDao.listByPodId(pod.getId());
        if (clusters.size() == 0) {
            pcs.add(new PodCluster(pod, null));
        } else {
            for (ClusterVO cluster : clusters) {
                pcs.add(new PodCluster(pod, cluster));
            }
        }
        return pcs;
    }

    @Override
    public Answer sendToSecStorage(HostVO ssHost, Command cmd) {
        if( ssHost.getType() == Host.Type.LocalSecondaryStorage ) {
            return  easySend(ssHost.getId(), cmd);
        } else if ( ssHost.getType() == Host.Type.SecondaryStorage) {
            return  sendToSSVM(ssHost.getDataCenterId(), cmd);
        } else {
            String msg = "do not support Secondary Storage type " + ssHost.getType();
            s_logger.warn(msg);
            return new Answer(cmd, false, msg);
        }
    }


    @Override
    public HostVO getSSAgent(HostVO ssHost) {
        if( ssHost.getType() == Host.Type.LocalSecondaryStorage ) {
            return  ssHost;
        } else if ( ssHost.getType() == Host.Type.SecondaryStorage) {
            Long dcId = ssHost.getDataCenterId();
            List<HostVO> ssAHosts = _hostDao.listSecondaryStorageVM(dcId);
            if (ssAHosts == null || ssAHosts.isEmpty() ) {
                return null;
            }
            Collections.shuffle(ssAHosts);
            return ssAHosts.get(0);
        }
        return null;
    }
    


    @Override
    public long sendToSecStorage(HostVO ssHost, Command cmd, Listener listener) {
        if( ssHost.getType() == Host.Type.LocalSecondaryStorage ) {
            return  gatherStats(ssHost.getId(), cmd, listener);
        } else if ( ssHost.getType() == Host.Type.SecondaryStorage) {
            return  sendToSSVM(ssHost.getDataCenterId(), cmd, listener);
        } else {
            s_logger.warn("do not support Secondary Storage type " + ssHost.getType());
        }
        return -1;
    }


    private long sendToSSVM(final long dcId, final Command cmd, final Listener listener) {
        List<HostVO> ssAHosts = _hostDao.listSecondaryStorageVM(dcId);
        if (ssAHosts == null || ssAHosts.isEmpty() ) {
            return -1;
        }
        Collections.shuffle(ssAHosts);
        HostVO ssAhost = ssAHosts.get(0);
        try {
            return send(ssAhost.getId(), new Commands(cmd), listener);
        } catch (final AgentUnavailableException e) {
            return -1;
        }
    }

    private Answer sendToSSVM(final long dcId, final Command cmd) {
        List<HostVO> ssAHosts = _hostDao.listSecondaryStorageVM(dcId);
        if (ssAHosts == null || ssAHosts.isEmpty() ) {
            return new Answer(cmd, false, "can not find secondary storage VM agent for data center " + dcId);
        }
        Collections.shuffle(ssAHosts);
        HostVO ssAhost = ssAHosts.get(0);
        return easySend(ssAhost.getId(), cmd);
    }

    @Override
    public Answer sendTo(Long dcId, HypervisorType type, Command cmd) {
        List<ClusterVO> clusters = _clusterDao.listByDcHyType(dcId, type.toString());
        int retry = 0;
        for (ClusterVO cluster : clusters) {
            List<HostVO> hosts = _hostDao.listBy(Host.Type.Routing, cluster.getId(), null, dcId);
            for (HostVO host : hosts) {
                retry++;
                if (retry > _retry) {
                    return null;
                }
                Answer answer = null;
                try {
                    answer = easySend(host.getId(), cmd);
                } catch (Exception e) {
                }
                if (answer != null) {
                    return answer;
                }
            }
        }
        return null;
    }

    protected int getPingInterval() {
        return _pingInterval;
    }

    @Override
    public Answer send(Long hostId, Command cmd) throws AgentUnavailableException, OperationTimedoutException {
        Commands cmds = new Commands(OnError.Stop);
        cmds.addCommand(cmd);
        send(hostId, cmds, cmd.getWait());
        Answer[] answers = cmds.getAnswers();
        if (answers != null && !(answers[0] instanceof UnsupportedAnswer)) {
            return answers[0];
        }

        if (answers != null && (answers[0] instanceof UnsupportedAnswer)) {
            s_logger.warn("Unsupported Command: " + answers[0].getDetails());
            return answers[0];
        }

        return null;
    }

    @DB
    protected boolean noDbTxn() {
        Transaction txn = Transaction.currentTxn();
        return !txn.dbTxnStarted();
    }

    @Override
    public Answer[] send(Long hostId, Commands commands, int timeout) throws AgentUnavailableException, OperationTimedoutException {
        assert hostId != null : "Who's not checking the agent id before sending?  ... (finger wagging)";
        if (hostId == null) {
            throw new AgentUnavailableException(-1);
        }

        assert noDbTxn() : "I know, I know.  Why are we so strict as to not allow txn across an agent call?  ...  Why are we so cruel ... Why are we such a dictator .... Too bad... Sorry...but NO AGENT COMMANDS WRAPPED WITHIN DB TRANSACTIONS!";

        Command[] cmds = commands.toCommands();

        assert cmds.length > 0 : "Ask yourself this about a hundred times.  Why am I  sending zero length commands?";

        if (cmds.length == 0) {
            commands.setAnswers(new Answer[0]);
        }

        final AgentAttache agent = getAttache(hostId);
        if (agent == null || agent.isClosed()) {
            throw new AgentUnavailableException("agent not logged into this management server", hostId);
        }

        Request req = new Request(hostId, _nodeId, cmds, commands.stopOnError(), true);
        req.setSequence(agent.getNextSequence());
        Answer[] answers = agent.send(req, timeout);
        notifyAnswersToMonitors(hostId, req.getSequence(), answers);
        commands.setAnswers(answers);
        return answers;
    }

    protected Status investigate(AgentAttache agent) {
        Long hostId = agent.getId();
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("checking if agent (" + hostId + ") is alive");
        }

        Answer answer = easySend(hostId, new CheckHealthCommand());
        if (answer != null && answer.getResult()) {
            Status status = Status.Up;
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("agent (" + hostId + ") responded to checkHeathCommand, reporting that agent is " + status);
            }
            return status;
        }

        return _haMgr.investigate(hostId);
    }

    protected AgentAttache getAttache(final Long hostId) throws AgentUnavailableException {
        assert (hostId != null) : "Who didn't check their id value?";
        if (hostId == null) {
            return null;
        }
        AgentAttache agent = findAttache(hostId);
        if (agent == null) {
            s_logger.debug("Unable to find agent for " + hostId);
            throw new AgentUnavailableException("Unable to find agent ", hostId);
        }

        return agent;
    }

    @Override
    public long send(Long hostId, Commands commands, Listener listener) throws AgentUnavailableException {
        final AgentAttache agent = getAttache(hostId);
        if (agent.isClosed()) {
            return -1;
        }

        Command[] cmds = commands.toCommands();

        assert cmds.length > 0 : "Why are you sending zero length commands?";
        if (cmds.length == 0) {
            return -1;
        }
        Request req = new Request(hostId, _nodeId, cmds, commands.stopOnError(), true);
        req.setSequence(agent.getNextSequence());
        agent.send(req, listener);
        return req.getSequence();
    }




    @Override
    public long gatherStats(Long hostId, Command cmd, Listener listener) {
        try {
            return send(hostId, new Commands(cmd), listener);
        } catch (final AgentUnavailableException e) {
            return -1;
        }
    }

    public void removeAgent(AgentAttache attache, Status nextState) {
        if (attache == null) {
            return;
        }
        long hostId = attache.getId();
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Remove Agent : " + hostId);
        }
        AgentAttache removed = null;
        boolean conflict = false;
        synchronized (_agents) {
            removed = _agents.remove(hostId);
            if (removed != null && removed != attache) {
                conflict = true;
                _agents.put(hostId, removed);
                removed = attache;
            }
        }
        if (conflict) {
            s_logger.debug("Agent for host " + hostId + " is created when it is being disconnected");
        }
        if (removed != null) {
            removed.disconnect(nextState);
        }
    }
    
    @Override
    public void updateStatus(HostVO host, Status.Event event) {
        _hostDao.updateStatus(host, event, _nodeId);
    }

    protected AgentAttache notifyMonitorsOfConnection(AgentAttache attache, final StartupCommand[] cmd, boolean forRebalance) throws ConnectionException {
        long hostId = attache.getId();
        HostVO host = _hostDao.findById(hostId);
        for (Pair<Integer, Listener> monitor : _hostMonitors) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Sending Connect to listener: " + monitor.second().getClass().getSimpleName());
            }
            for (int i = 0; i < cmd.length; i++) {
                try {
                    monitor.second().processConnect(host, cmd[i], forRebalance);
                } catch (Exception e) {
                    if (e instanceof ConnectionException) {
                        ConnectionException ce = (ConnectionException)e;
                        if (ce.isSetupError()) {
                            s_logger.warn("Monitor " + monitor.second().getClass().getSimpleName() + " says there is an error in the connect process for " + hostId + " due to " + e.getMessage());
                            handleDisconnectWithoutInvestigation(attache, Event.AgentDisconnected);
                            throw ce;
                        } else {
                            s_logger.info("Monitor " + monitor.second().getClass().getSimpleName() + " says not to continue the connect process for " + hostId + " due to " + e.getMessage());
                            handleDisconnectWithoutInvestigation(attache, Event.ShutdownRequested);
                            return attache;
                        }
                    } else if (e instanceof HypervisorVersionChangedException) {
                    	handleDisconnectWithoutInvestigation(attache, Event.HypervisorVersionChanged);
                        throw new CloudRuntimeException("Unable to connect " + attache.getId(), e);
                    } else {
                        s_logger.error("Monitor " + monitor.second().getClass().getSimpleName() + " says there is an error in the connect process for " + hostId + " due to " + e.getMessage(), e);
                        handleDisconnectWithoutInvestigation(attache, Event.AgentDisconnected);
                        throw new CloudRuntimeException("Unable to connect " + attache.getId(), e);
                    }
                }
            }
        }

        Long dcId = host.getDataCenterId();
        ReadyCommand ready = new ReadyCommand(dcId);
        Answer answer = easySend(hostId, ready);
        if (answer == null || !answer.getResult()) {
            // this is tricky part for secondary storage
            // make it as disconnected, wait for secondary storage VM to be up
            // return the attache instead of null, even it is disconnectede
        	handleDisconnectWithoutInvestigation(attache, Event.AgentDisconnected);
        }

        _hostDao.updateStatus(host, Event.Ready, _nodeId);
        attache.ready();
        return attache;
    }

    protected boolean notifyCreatorsOfConnection(StartupCommand[] cmd) throws ConnectionException {
        boolean handled = false;
        for (Pair<Integer, StartupCommandProcessor> monitor : _creationMonitors) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Sending Connect to creator: "
                        + monitor.second().getClass().getSimpleName());
            }
            handled =  monitor.second().processInitialConnect(cmd);
            if (handled) {
                break;
            }
        }

        return handled;
    }

    @Override
    public boolean start() {
        startDirectlyConnectedHosts();
        if (_monitor != null) {
            _monitor.start();
        }
        if (_connection != null) {
            _connection.start();
        }

        return true;
    }

    public void startDirectlyConnectedHosts() {
        List<HostVO> hosts = _hostDao.findDirectlyConnectedHosts();
        for (HostVO host : hosts) {
            loadDirectlyConnectedHost(host, false);
        }
    }

    @SuppressWarnings("rawtypes")
    protected boolean loadDirectlyConnectedHost(HostVO host, boolean forRebalance) {
    	boolean initialized = false;
        ServerResource resource = null;
    	try {
	        String resourceName = host.getResource();
	        try {
	            Class<?> clazz = Class.forName(resourceName);
	            Constructor constructor = clazz.getConstructor();
	            resource = (ServerResource) constructor.newInstance();
	        } catch (ClassNotFoundException e) {
	            s_logger.warn("Unable to find class " + host.getResource(), e);
	            return false;
	        } catch (InstantiationException e) {
	            s_logger.warn("Unablet to instantiate class " + host.getResource(), e);
	            return false;
	        } catch (IllegalAccessException e) {
	            s_logger.warn("Illegal access " + host.getResource(), e);
	            return false;
	        } catch (SecurityException e) {
	            s_logger.warn("Security error on " + host.getResource(), e);
	            return false;
	        } catch (NoSuchMethodException e) {
	            s_logger.warn("NoSuchMethodException error on " + host.getResource(), e);
	            return false;
	        } catch (IllegalArgumentException e) {
	            s_logger.warn("IllegalArgumentException error on " + host.getResource(), e);
	            return false;
	        } catch (InvocationTargetException e) {
	            s_logger.warn("InvocationTargetException error on " + host.getResource(), e);
	            return false;
	        }
	
	        _hostDao.loadDetails(host);
	
	        HashMap<String, Object> params = new HashMap<String, Object>(host.getDetails().size() + 5);
	        params.putAll(host.getDetails());
	
	        params.put("guid", host.getGuid());
	        params.put("zone", Long.toString(host.getDataCenterId()));
	        if (host.getPodId() != null) {
	            params.put("pod", Long.toString(host.getPodId()));
	        }
	        if (host.getClusterId() != null) {
	            params.put("cluster", Long.toString(host.getClusterId()));
	            String guid = null;
	            ClusterVO cluster = _clusterDao.findById(host.getClusterId());
	            if (cluster.getGuid() == null) {
	                guid = host.getDetail("pool");
	            } else {
	                guid = cluster.getGuid();
	            }
	            if (guid != null && !guid.isEmpty()) {
	                params.put("pool", guid);
	            }
	        }
	
	        params.put("ipaddress", host.getPrivateIpAddress());
	        params.put("secondary.storage.vm", "false");
	        params.put("max.template.iso.size", _configDao.getValue(Config.MaxTemplateAndIsoSize.toString()));
	        params.put("migratewait", _configDao.getValue(Config.MigrateWait.toString()));
	
	        try {
	            resource.configure(host.getName(), params);
	        } catch (ConfigurationException e) {
	            s_logger.warn("Unable to configure resource due to ", e);
	            return false;
	        }
	
	        if (!resource.start()) {
	            s_logger.warn("Unable to start the resource");
	            return false;
	        }
        
	        initialized = true;
    	} finally {
    		if(!initialized) {
                if (host != null) {
                    _hostDao.updateStatus(host, Event.AgentDisconnected, _nodeId);
                }
    		}	
    	}

        if (forRebalance) {
            Host h = _resourceMgr.createHostAndAgent(host.getId(), resource, host.getDetails(), false, null, null, true);
            return (h == null ? false : true);
        } else {
            _executor.execute(new SimulateStartTask(host.getId(), resource, host.getDetails(), null));
            return true;
        }
    }

    protected AgentAttache createAttacheForDirectConnect(HostVO host, ServerResource resource)
            throws ConnectionException {
        if (resource instanceof DummySecondaryStorageResource || resource instanceof KvmDummyResourceBase) {
            return new DummyAttache(this, host.getId(), false);
        }
        
        s_logger.debug("create DirectAgentAttache for " + host.getId());
        DirectAgentAttache attache = new DirectAgentAttache(this, host.getId(), resource, host.isInMaintenanceStates(), this);
        
        AgentAttache old = null;
        synchronized (_agents) {
            old = _agents.put(host.getId(), attache);
        }
        if (old != null) {
            old.disconnect(Status.Removed);
        }

        return attache;
    }
    
    @Override
    public boolean stop() {
        if (_monitor != null) {
            _monitor.signalStop();
        }
        if (_connection != null) {
            _connection.stop();
        }

        s_logger.info("Disconnecting agents: " + _agents.size());
        synchronized (_agents) {
            for (final AgentAttache agent : _agents.values()) {
                final HostVO host = _hostDao.findById(agent.getId());
                if (host == null) {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Cant not find host " + agent.getId());
                    }
                } else {
                    _hostDao.updateStatus(host, Event.ManagementServerDown, _nodeId);
                }
            }
        }
        return true;
    }

    @Override
    public Pair<HostPodVO, Long> findPod(final VirtualMachineTemplate template, ServiceOfferingVO offering, final DataCenterVO dc, final long accountId, Set<Long> avoids) {
        final Enumeration en = _podAllocators.enumeration();
        while (en.hasMoreElements()) {
            final PodAllocator allocator = (PodAllocator) en.nextElement();
            final Pair<HostPodVO, Long> pod = allocator.allocateTo(template, offering, dc, accountId, avoids);
            if (pod != null) {
                return pod;
            }
        }
        return null;
    }

    @Override
    public HostStats getHostStatistics(long hostId) {
        Answer answer = easySend(hostId, new GetHostStatsCommand(_hostDao.findById(hostId).getGuid(), _hostDao.findById(hostId).getName(), hostId));

        if (answer != null && (answer instanceof UnsupportedAnswer)) {
            return null;
        }

        if (answer == null || !answer.getResult()) {
            String msg = "Unable to obtain host " + hostId + " statistics. ";
            s_logger.warn(msg);
            return null;
        } else {

            // now construct the result object
            if (answer instanceof GetHostStatsAnswer) {
                return ((GetHostStatsAnswer) answer).getHostStats();
            }
        }
        return null;
    }

    @Override
    public Long getGuestOSCategoryId(long hostId) {
        HostVO host = _hostDao.findById(hostId);
        if (host == null) {
            return null;
        } else {
            _hostDao.loadDetails(host);
            DetailVO detail = _hostDetailsDao.findDetail(hostId, "guest.os.category.id");
            if (detail == null) {
                return null;
            } else {
                return Long.parseLong(detail.getValue());
            }
        }
    }

    @Override
    public String getHostTags(long hostId) {
        List<String> hostTags = _hostTagsDao.gethostTags(hostId);
        if (hostTags == null) {
            return null;
        } else {
            return StringUtils.listToCsvTags(hostTags);
        }
    }

    @Override
    public String getName() {
        return _name;
    }
   
    protected boolean handleDisconnectWithoutInvestigation(AgentAttache attache, Status.Event event) {
        long hostId = attache.getId();

        s_logger.info("Host " + hostId + " is disconnecting with event " + event);
        Status nextStatus = null;
        HostVO host = _hostDao.findById(hostId);
        if (host == null) {
            s_logger.warn("Can't find host with " + hostId);
            nextStatus = Status.Removed;
        } else {
            final Status currentStatus = host.getStatus();
            if (currentStatus == Status.Down || currentStatus == Status.Alert || currentStatus == Status.Removed) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Host " + hostId + " is already " + currentStatus);
                }
                nextStatus = currentStatus;
            } else {
                try {
                    nextStatus = currentStatus.getNextStatus(event);
                } catch (NoTransitionException e) {
                    String err = "Cannot find next status for " + event + " as current status is " + currentStatus + " for agent " + hostId;
                    s_logger.debug(err);
                    throw new CloudRuntimeException(err);
                }
                
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("The next status of agent " + hostId + "is " + nextStatus + ", current status is " + currentStatus);
                }
            }
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Deregistering link for " + hostId + " with state " + nextStatus);
        }

        removeAgent(attache, nextStatus);
        disconnectAgent(host, event, _nodeId);

        for (Pair<Integer, Listener> monitor : _hostMonitors) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Sending Disconnect to listener: " + monitor.second().getClass().getName());
            }
            monitor.second().processDisconnect(hostId, nextStatus);
        }
        return true;
    }
    
    protected boolean handleDisconnectWithInvestigation(AgentAttache attache, Status.Event event) {
        long hostId = attache.getId();
        HostVO host = _hostDao.findById(hostId);
        if (host != null) {
            Status nextStatus = null;
            try {
                nextStatus = host.getStatus().getNextStatus(event);
            } catch (NoTransitionException ne) {
                /* Agent may be currently in status of Down, Alert, Removed, namely there is no next status for some events.
                 * Why this can happen? Ask God not me. I hate there was no piece of comment for code handling race condition.
                 * God knew what race condition the code dealt with! 
                 */
            }
            
            if (nextStatus == Status.Alert) {
                /* OK, we are going to the bad status, let's see what happened */
                s_logger.info("Investigating why host " + hostId + " has disconnected with event " + event);

                final Status determinedState = investigate(attache);
                final Status currentStatus = host.getStatus();
                s_logger.info("The state determined is " + determinedState);

                if (determinedState == null || determinedState == Status.Down) {
                    s_logger.error("Host is down: " + host.getId() + "-" + host.getName() + ".  Starting HA on the VMs");
                    event = Status.Event.HostDown;
                } else if (determinedState == Status.Up) {
                    /* Got ping response from host, bring it back*/
                    s_logger.info("Agent is determined to be up and running");
                    agentStatusTransitTo(host, Status.Event.Ping, _nodeId);
                    return false;
                } else if (determinedState == Status.Disconnected) {
                    s_logger.warn("Agent is disconnected but the host is still up: " + host.getId() + "-" + host.getName());
                    if (currentStatus == Status.Disconnected) {
                        if (((System.currentTimeMillis() >> 10) - host.getLastPinged()) > _alertWait) {
                            s_logger.warn("Host " + host.getId() + " has been disconnected pass the time it should be disconnected.");
                            event = Status.Event.WaitedTooLong;
                        } else {
                            s_logger.debug("Host has been determined to be disconnected but it hasn't passed the wait time yet.");
                            return false;
                        }
                    } else if (currentStatus == Status.Updating) {
                        if (((System.currentTimeMillis() >> 10) - host.getLastPinged()) > _updateWait) {
                            s_logger.warn("Host " + host.getId() + " has been updating for too long");
                            event = Status.Event.WaitedTooLong;
                        } else {
                            s_logger.debug("Host has been determined to be disconnected but it hasn't passed the wait time yet.");
                            return false;
                        }
                    } else if (currentStatus == Status.Up) {
                        DataCenterVO dcVO = _dcDao.findById(host.getDataCenterId());
                        HostPodVO podVO = _podDao.findById(host.getPodId());
                        String hostDesc = "name: " + host.getName() + " (id:" + host.getId() + "), availability zone: " + dcVO.getName() + ", pod: " + podVO.getName();
                        if ((host.getType() != Host.Type.SecondaryStorage) && (host.getType() != Host.Type.ConsoleProxy)) {
                            _alertMgr.sendAlert(AlertManager.ALERT_TYPE_HOST, host.getDataCenterId(), host.getPodId(), "Host disconnected, " + hostDesc, "If the agent for host [" + hostDesc
                                    + "] is not restarted within " + _alertWait + " seconds, HA will begin on the VMs");
                        }
                        event = Status.Event.AgentDisconnected;
                    }
                } else {
                    // if we end up here we are in alert state, send an alert
                    DataCenterVO dcVO = _dcDao.findById(host.getDataCenterId());
                    HostPodVO podVO = _podDao.findById(host.getPodId());
                    String hostDesc = "name: " + host.getName() + " (id:" + host.getId() + "), availability zone: " + dcVO.getName() + ", pod: " + podVO.getName();
                    _alertMgr.sendAlert(AlertManager.ALERT_TYPE_HOST, host.getDataCenterId(), host.getPodId(), "Host in ALERT state, " + hostDesc, "In availability zone " + host.getDataCenterId()
                            + ", host is in alert state: " + host.getId() + "-" + host.getName());
                }
            } else {
                s_logger.debug("The next status of Agent " + host.getId() + " is not Alert, no need to investigate what happened");
            }
        }
        
        handleDisconnectWithoutInvestigation(attache, event);
        /*TODO: call HA manager in monitors
         *         host = _hostDao.findById(host.getId());
        if (!event.equals(Event.PrepareUnmanaged) && !event.equals(Event.HypervisorVersionChanged) && (host.getStatus() == Status.Alert || host.getStatus() == Status.Down)) {
            _haMgr.scheduleRestartForVmsOnHost(host, investigate);
        }
         * */
        return true;
    }

    protected class DisconnectTask implements Runnable {
        AgentAttache _attache;
        Status.Event _event;
        boolean _investigate;

        DisconnectTask(final AgentAttache attache, final Status.Event event, final boolean investigate) {
            _attache = attache;
            _event = event;
            _investigate = investigate;
        }

        @Override
        public void run() {
        	try {
                if (_investigate == true) {
                    handleDisconnectWithInvestigation(_attache, _event);
                } else {
                	handleDisconnectWithoutInvestigation(_attache, _event);
                }
            } catch (final Exception e) {
                s_logger.error("Exception caught while handling disconnect: ", e);
            } finally {
                StackMaid.current().exitCleanup();
            }
        }
    }

    @Override
    public Answer easySend(final Long hostId, final Command cmd) {
        try {
            Host h = _hostDao.findById(hostId);
            if (h == null || h.getRemoved() != null) {
                s_logger.debug("Host with id " + hostId + " doesn't exist");
                return null;
            }
            Status status = h.getStatus();
            if (!status.equals(Status.Up) && !status.equals(Status.Connecting)) {
                return null;
            }
            final Answer answer = send(hostId, cmd);
            if (answer == null) {
                s_logger.warn("send returns null answer");
                return null;
            }

            if (s_logger.isDebugEnabled() && answer.getDetails() != null) {
                s_logger.debug("Details from executing " + cmd.getClass() + ": " + answer.getDetails());
            }

            return answer;

        } catch (final AgentUnavailableException e) {
            s_logger.warn(e.getMessage());
            return null;
        } catch (final OperationTimedoutException e) {
            s_logger.warn("Operation timed out: " + e.getMessage());
            return null;
        } catch (final Exception e) {
            s_logger.warn("Exception while sending", e);
            return null;
        }
    }

    @Override
    public Answer[] send(final Long hostId, Commands cmds) throws AgentUnavailableException, OperationTimedoutException {
        int wait = 0;
        for( Command cmd : cmds ) {
            if ( cmd.getWait() > wait ) {
                wait = cmd.getWait();
            }
        }
        if ( wait == 0 ) {
            wait = _wait;
        }
        return send(hostId, cmds, wait);
    }

    @Override
    public boolean reconnect(final long hostId) {
        HostVO host;

        host = _hostDao.findById(hostId);
        if (host == null || host.getRemoved() != null) {
            s_logger.warn("Unable to find host " + hostId);
            return false;
        }

        if (host.getStatus() != Status.Up && host.getStatus() != Status.Alert) {
            s_logger.info("Unable to disconnect host because it is not in the correct state: host=" + hostId + "; Status=" + host.getStatus());
            return false;
        }

        AgentAttache attache = findAttache(hostId);
        if (attache == null) {
            s_logger.info("Unable to disconnect host because it is not connected to this server: " + hostId);
            return false;
        }

        disconnectWithoutInvestigation(attache, Event.ShutdownRequested);
        return true;
    }

    @Override
    public boolean executeUserRequest(long hostId, Event event) throws AgentUnavailableException {
         if (event == Event.AgentDisconnected) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Received agent disconnect event for host " + hostId);
            }
            AgentAttache attache = null;
            attache = findAttache(hostId);
            if (attache != null) {
            	handleDisconnectWithoutInvestigation(attache, Event.AgentDisconnected);
            }
            return true;
        } else if (event == Event.ShutdownRequested) {
            return reconnect(hostId);
        }
        return false;
    }

    public boolean checkCIDR(Host.Type type, HostPodVO pod, String serverPrivateIP, String serverPrivateNetmask) {
        if (serverPrivateIP == null) {
            return true;
        }
        // Get the CIDR address and CIDR size
        String cidrAddress = pod.getCidrAddress();
        long cidrSize = pod.getCidrSize();

        // If the server's private IP address is not in the same subnet as the
        // pod's CIDR, return false
        String cidrSubnet = NetUtils.getCidrSubNet(cidrAddress, cidrSize);
        String serverSubnet = NetUtils.getSubNet(serverPrivateIP, serverPrivateNetmask);
        if (!cidrSubnet.equals(serverSubnet)) {
            return false;
        }

        // If the server's private netmask is less inclusive than the pod's CIDR
        // netmask, return false
        String cidrNetmask = NetUtils.getCidrSubNet("255.255.255.255", cidrSize);
        long cidrNetmaskNumeric = NetUtils.ip2Long(cidrNetmask);
        long serverNetmaskNumeric = NetUtils.ip2Long(serverPrivateNetmask);
        if (serverNetmaskNumeric > cidrNetmaskNumeric) {
            return false;
        }
        return true;
    }

    protected void checkCIDR(Host.Type type, HostPodVO pod, DataCenterVO dc, String serverPrivateIP, String serverPrivateNetmask) throws IllegalArgumentException {
        // Skip this check for Storage Agents and Console Proxies
        if (type == Host.Type.Storage || type == Host.Type.ConsoleProxy) {
            return;
        }

        if (serverPrivateIP == null) {
            return;
        }
        // Get the CIDR address and CIDR size
        String cidrAddress = pod.getCidrAddress();
        long cidrSize = pod.getCidrSize();

        // If the server's private IP address is not in the same subnet as the
        // pod's CIDR, return false
        String cidrSubnet = NetUtils.getCidrSubNet(cidrAddress, cidrSize);
        String serverSubnet = NetUtils.getSubNet(serverPrivateIP, serverPrivateNetmask);
        if (!cidrSubnet.equals(serverSubnet)) {
            s_logger.warn("The private ip address of the server (" + serverPrivateIP + ") is not compatible with the CIDR of pod: " + pod.getName() + " and zone: " + dc.getName());
            throw new IllegalArgumentException("The private ip address of the server (" + serverPrivateIP + ") is not compatible with the CIDR of pod: " + pod.getName() + " and zone: " + dc.getName());
        }

        // If the server's private netmask is less inclusive than the pod's CIDR
        // netmask, return false
        String cidrNetmask = NetUtils.getCidrSubNet("255.255.255.255", cidrSize);
        long cidrNetmaskNumeric = NetUtils.ip2Long(cidrNetmask);
        long serverNetmaskNumeric = NetUtils.ip2Long(serverPrivateNetmask);
        if (serverNetmaskNumeric > cidrNetmaskNumeric) {
            throw new IllegalArgumentException("The private ip address of the server (" + serverPrivateIP + ") is not compatible with the CIDR of pod: " + pod.getName() + " and zone: " + dc.getName());
        }

    }

    public void checkIPConflicts(Host.Type type, HostPodVO pod, DataCenterVO dc, String serverPrivateIP, String serverPrivateNetmask, String serverPublicIP, String serverPublicNetmask) {
        // If the server's private IP is the same as is public IP, this host has
        // a host-only private network. Don't check for conflicts with the
        // private IP address table.
        if (serverPrivateIP != serverPublicIP) {
            if (!_privateIPAddressDao.mark(dc.getId(), pod.getId(), serverPrivateIP)) {
                // If the server's private IP address is already in the
                // database, return false
                List<DataCenterIpAddressVO> existingPrivateIPs = _privateIPAddressDao.listByPodIdDcIdIpAddress(pod.getId(), dc.getId(), serverPrivateIP);

                assert existingPrivateIPs.size() <= 1 : " How can we get more than one ip address with " + serverPrivateIP;
                if (existingPrivateIPs.size() > 1) {
                    throw new IllegalArgumentException("The private ip address of the server (" + serverPrivateIP + ") is already in use in pod: " + pod.getName() + " and zone: " + dc.getName());
                }
                if (existingPrivateIPs.size() == 1) {
                    DataCenterIpAddressVO vo = existingPrivateIPs.get(0);
                    if (vo.getInstanceId() != null) {
                        throw new IllegalArgumentException("The private ip address of the server (" + serverPrivateIP + ") is already in use in pod: " + pod.getName() + " and zone: " + dc.getName());
                    }
                }
            }
        }

        if (serverPublicIP != null && !_publicIPAddressDao.mark(dc.getId(), new Ip(serverPublicIP))) {
            // If the server's public IP address is already in the database,
            // return false
            List<IPAddressVO> existingPublicIPs = _publicIPAddressDao.listByDcIdIpAddress(dc.getId(), serverPublicIP);
            if (existingPublicIPs.size() > 0) {
                throw new IllegalArgumentException("The public ip address of the server (" + serverPublicIP + ") is already in use in zone: " + dc.getName());
            }
        }
    }

    protected AgentAttache createAttacheForConnect(HostVO host, Link link) throws ConnectionException {
        s_logger.debug("create ConnectedAgentAttache for " + host.getId());
        AgentAttache attache = new ConnectedAgentAttache(this, host.getId(), link, host.isInMaintenanceStates());
        link.attach(attache);
        
        AgentAttache old = null;
        synchronized (_agents) {
            old = _agents.put(host.getId(), attache);
        }
        if (old != null) {
            old.disconnect(Status.Removed);
        }

        return attache;
    }
    
    //TODO: handle mycloud specific
    private AgentAttache handleConnectedAgent(final Link link, final StartupCommand[] startup) throws ConnectionException {
        AgentAttache attache = null;
        HostVO host = _resourceMgr.createHostVOForConnectedAgent(startup);
        if (host != null) {
            attache = createAttacheForConnect(host, link);
        }
        
        attache = notifyMonitorsOfConnection(attache, startup, false);
        
        return attache;
    }
       
    // protected void upgradeAgent(final Link link, final byte[] request, final
    // String reason) {
    //
    // if (reason == UnsupportedVersionException.IncompatibleVersion) {
    // final UpgradeResponse response = new UpgradeResponse(request,
    // _upgradeMgr.getAgentUrl());
    // try {
    // s_logger.info("Asking for the agent to update due to incompatible version: "
    // + response.toString());
    // link.send(response.toBytes());
    // } catch (final ClosedChannelException e) {
    // s_logger.warn("Unable to send response due to connection closed: " +
    // response.toString());
    // }
    // return;
    // }
    //
    // assert (reason == UnsupportedVersionException.UnknownVersion) :
    // "Unknown reason: " + reason;
    // final UpgradeResponse response = new UpgradeResponse(request,
    // _upgradeMgr.getAgentUrl());
    // try {
    // s_logger.info("Asking for the agent to update due to unknown version: " +
    // response.toString());
    // link.send(response.toBytes());
    // } catch (final ClosedChannelException e) {
    // s_logger.warn("Unable to send response due to connection closed: " +
    // response.toString());
    // }
    // }

    protected class SimulateStartTask implements Runnable {
        ServerResource resource;
        Map<String, String> details;
        long id;
        ActionDelegate<Long> actionDelegate;

        public SimulateStartTask(long id, ServerResource resource, Map<String, String> details, ActionDelegate<Long> actionDelegate) {
            this.id = id;
            this.resource = resource;
            this.details = details;
            this.actionDelegate = actionDelegate;
        }

        @Override
        public void run() {
            try {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Simulating start for resource " + resource.getName() + " id " + id);
                }
                
                _resourceMgr.createHostAndAgent(id, resource, details, false, null, null, false);
            } catch (Exception e) {
                s_logger.warn("Unable to simulate start on resource " + id + " name " + resource.getName(), e);
            } finally {
                if (actionDelegate != null) {
                    actionDelegate.action(new Long(id));
                }
                StackMaid.current().exitCleanup();
            }
        }
    }

    public class AgentHandler extends Task {
        public AgentHandler(Task.Type type, Link link, byte[] data) {
            super(type, link, data);
        }

        protected void processRequest(final Link link, final Request request) {
            AgentAttache attache = (AgentAttache) link.attachment();
            final Command[] cmds = request.getCommands();
            Command cmd = cmds[0];
            boolean logD = true;

            Response response = null;
            if (attache == null) {
                request.logD("Processing the first command ");
                if (!(cmd instanceof StartupCommand)) {
                    s_logger.warn("Throwing away a request because it came through as the first command on a connect: " + request);
                    return;
                }
                StartupCommand startup = (StartupCommand) cmd;
                // if ((_upgradeMgr.registerForUpgrade(-1, startup.getVersion())
                // == UpgradeManager.State.RequiresUpdate) &&
                // (_upgradeMgr.getAgentUrl() != null)) {
                // final UpgradeCommand upgrade = new
                // UpgradeCommand(_upgradeMgr.getAgentUrl());
                // final Request req = new Request(1, -1, -1, new Command[] {
                // upgrade }, true, true);
                // s_logger.info("Agent requires upgrade: " + req.toString());
                // try {
                // link.send(req.toBytes());
                // } catch (ClosedChannelException e) {
                // s_logger.warn("Unable to tell agent it should update.");
                // }
                // return;
                // }
                try {
                    StartupCommand[] startups = new StartupCommand[cmds.length];
                    for (int i = 0; i < cmds.length; i++) {
                        startups[i] = (StartupCommand) cmds[i];
                    }
                    attache = handleConnectedAgent(link, startups);
                } catch (final IllegalArgumentException e) {
                    _alertMgr.sendAlert(AlertManager.ALERT_TYPE_HOST, 0, new Long(0), "Agent from " + startup.getPrivateIpAddress() + " is unable to connect due to " + e.getMessage(), "Agent from "
                            + startup.getPrivateIpAddress() + " is unable to connect with " + request + " because of " + e.getMessage());
                    s_logger.warn("Unable to create attache for agent: " + request, e);
                    response = new Response(request, new StartupAnswer((StartupCommand) cmd, e.getMessage()), _nodeId, -1);
                } catch (ConnectionException e) {
                    _alertMgr.sendAlert(AlertManager.ALERT_TYPE_HOST, 0, new Long(0), "Agent from " + startup.getPrivateIpAddress() + " is unable to connect due to " + e.getMessage(), "Agent from "
                            + startup.getPrivateIpAddress() + " is unable to connect with " + request + " because of " + e.getMessage());
                    s_logger.warn("Unable to create attache for agent: " + request, e);
                    response = new Response(request, new StartupAnswer((StartupCommand) cmd, e.getMessage()), _nodeId, -1);
                } catch (final CloudRuntimeException e) {
                    _alertMgr.sendAlert(AlertManager.ALERT_TYPE_HOST, 0, new Long(0), "Agent from " + startup.getPrivateIpAddress() + " is unable to connect due to " + e.getMessage(), "Agent from "
                            + startup.getPrivateIpAddress() + " is unable to connect with " + request + " because of " + e.getMessage());
                    s_logger.warn("Unable to create attache for agent: " + request, e);
                }
                if (attache == null) {
                    if (response == null) {
                        s_logger.warn("Unable to create attache for agent: " + request);
                        response = new Response(request, new StartupAnswer((StartupCommand) request.getCommand(), "Unable to register this agent"), _nodeId, -1);
                    }
                    try {
                        link.send(response.toBytes(), true);
                    } catch (final ClosedChannelException e) {
                        s_logger.warn("Response was not sent: " + response);
                    }
                    return;
                }
            }

            final long hostId = attache.getId();

            if (s_logger.isDebugEnabled()) {
                if (cmd instanceof PingRoutingCommand) {
                    final PingRoutingCommand ping = (PingRoutingCommand) cmd;
                    if (ping.getNewStates().size() > 0) {
                        s_logger.debug("SeqA " + hostId + "-" + request.getSequence() + ": Processing " + request);
                    } else {
                        logD = false;
                        s_logger.debug("Ping from " + hostId);
                        s_logger.trace("SeqA " + hostId + "-" + request.getSequence() + ": Processing " + request);
                    }
                } else if (cmd instanceof PingCommand) {
                    logD = false;
                    s_logger.debug("Ping from " + hostId);
                    s_logger.trace("SeqA " + attache.getId() + "-" + request.getSequence() + ": Processing " + request);
                } else {
                    s_logger.debug("SeqA " + attache.getId() + "-" + request.getSequence() + ": Processing " + request);
                }
            }

            final Answer[] answers = new Answer[cmds.length];
            for (int i = 0; i < cmds.length; i++) {
                cmd = cmds[i];
                Answer answer = null;
                try {
                    if (cmd instanceof StartupRoutingCommand) {
                        final StartupRoutingCommand startup = (StartupRoutingCommand) cmd;
                        answer = new StartupAnswer(startup, attache.getId(), getPingInterval());
                    } else if (cmd instanceof StartupProxyCommand) {
                        final StartupProxyCommand startup = (StartupProxyCommand) cmd;
                        answer = new StartupAnswer(startup, attache.getId(), getPingInterval());
                    } else if (cmd instanceof StartupSecondaryStorageCommand) {
                        final StartupSecondaryStorageCommand startup = (StartupSecondaryStorageCommand) cmd;
                        answer = new StartupAnswer(startup, attache.getId(), getPingInterval());
                    } else if (cmd instanceof StartupStorageCommand) {
                        final StartupStorageCommand startup = (StartupStorageCommand) cmd;
                        answer = new StartupAnswer(startup, attache.getId(), getPingInterval());
                    } else if (cmd instanceof ShutdownCommand) {
                        final ShutdownCommand shutdown = (ShutdownCommand) cmd;
                        final String reason = shutdown.getReason();
                        s_logger.info("Host " + attache.getId() + " has informed us that it is shutting down with reason " + reason + " and detail " + shutdown.getDetail());
                        if (reason.equals(ShutdownCommand.Update)) {
                            disconnectWithoutInvestigation(attache, Event.UpdateNeeded);
                        } else if (reason.equals(ShutdownCommand.Requested)) {
                            disconnectWithoutInvestigation(attache, Event.ShutdownRequested);
                        }
                        return;
                    } else if (cmd instanceof AgentControlCommand) {
                        answer = handleControlCommand(attache, (AgentControlCommand) cmd);
                    } else {
                        handleCommands(attache, request.getSequence(), new Command[] { cmd });
                        if (cmd instanceof PingCommand) {
                            long cmdHostId = ((PingCommand) cmd).getHostId();

                            // if the router is sending a ping, verify the
                            // gateway was pingable
                            if (cmd instanceof PingRoutingCommand) {
                                boolean gatewayAccessible = ((PingRoutingCommand) cmd).isGatewayAccessible();
                                HostVO host = _hostDao.findById(Long.valueOf(cmdHostId));
                                if (!gatewayAccessible) {
                                    // alert that host lost connection to
                                    // gateway (cannot ping the default route)
                                    DataCenterVO dcVO = _dcDao.findById(host.getDataCenterId());
                                    HostPodVO podVO = _podDao.findById(host.getPodId());
                                    String hostDesc = "name: " + host.getName() + " (id:" + host.getId() + "), availability zone: " + dcVO.getName() + ", pod: " + podVO.getName();

                                    _alertMgr.sendAlert(AlertManager.ALERT_TYPE_ROUTING, host.getDataCenterId(), host.getPodId(), "Host lost connection to gateway, " + hostDesc, "Host [" + hostDesc
                                            + "] lost connection to gateway (default route) and is possibly having network connection issues.");
                                } else {
                                    _alertMgr.clearAlert(AlertManager.ALERT_TYPE_ROUTING, host.getDataCenterId(), host.getPodId());
                                }
                            }
                            answer = new PingAnswer((PingCommand) cmd);
                        } else if (cmd instanceof ReadyAnswer) {
                            HostVO host = _hostDao.findById(attache.getId());
                            if (host == null) {
                                if (s_logger.isDebugEnabled()) {
                                    s_logger.debug("Cant not find host " + attache.getId());
                                }
                            }
                            answer = new Answer(cmd);
                        } else {
                            answer = new Answer(cmd);
                        }
                    }
                } catch (final Throwable th) {
                    s_logger.warn("Caught: ", th);
                    answer = new Answer(cmd, false, th.getMessage());
                }
                answers[i] = answer;
            }

            response = new Response(request, answers, _nodeId, attache.getId());
            if (s_logger.isDebugEnabled()) {
                if (logD) {
                    s_logger.debug("SeqA " + attache.getId() + "-" + response.getSequence() + ": Sending " + response);
                } else {
                    s_logger.trace("SeqA " + attache.getId() + "-" + response.getSequence() + ": Sending " + response);
                }
            }
            try {
                link.send(response.toBytes());
            } catch (final ClosedChannelException e) {
                s_logger.warn("Unable to send response because connection is closed: " + response);
            }
        }

        protected void processResponse(final Link link, final Response response) {
            final AgentAttache attache = (AgentAttache) link.attachment();
            if (attache == null) {
                s_logger.warn("Unable to process: " + response);
            }

            if (!attache.processAnswers(response.getSequence(), response)) {
                s_logger.info("Host " + attache.getId() + " - Seq " + response.getSequence() + ": Response is not processed: " + response);
            }
        }

        @Override
        protected void doTask(final Task task) throws Exception {
            Transaction txn = Transaction.open(Transaction.CLOUD_DB);
            try {
                final Type type = task.getType();
                if (type == Task.Type.DATA) {
                    final byte[] data = task.getData();
                    try {
                        final Request event = Request.parse(data);
                        if (event instanceof Response) {
                            processResponse(task.getLink(), (Response) event);
                        } else {
                            processRequest(task.getLink(), event);
                        }
                    } catch (final UnsupportedVersionException e) {
                        s_logger.warn(e.getMessage());
                        // upgradeAgent(task.getLink(), data, e.getReason());
                    }
                } else if (type == Task.Type.CONNECT) {
                } else if (type == Task.Type.DISCONNECT) {
                    final Link link = task.getLink();
                    final AgentAttache attache = (AgentAttache) link.attachment();
                    if (attache != null) {
                        disconnectWithInvestigation(attache, Event.AgentDisconnected);
                    } else {
                        s_logger.info("Connection from " + link.getIpAddress() + " closed but no cleanup was done.");
                        link.close();
                        link.terminated();
                    }
                }
            } finally {
                StackMaid.current().exitCleanup();
                txn.close();
            }
        }
    }

    protected AgentManagerImpl() {
    }

	@Override
    public boolean tapLoadingAgents(Long hostId, TapAgentsAction action) {
        synchronized (_loadingAgents) {
            if (action == TapAgentsAction.Add) {
                _loadingAgents.add(hostId);
            } else if (action == TapAgentsAction.Del) {
                _loadingAgents.remove(hostId);
            } else if (action == TapAgentsAction.Contains) {
                return _loadingAgents.contains(hostId);
            } else {
                throw new CloudRuntimeException("Unkonwn TapAgentsAction " + action);
            }
        }  
        return true;
    }

    private boolean isAgentEventAllowedByResourceState(HostVO host, Status.Event event) {
        ResourceState state = host.getResourceState();
        boolean allow = true;
        if (state == ResourceState.Enabled) {
           
        } else if (state == ResourceState.Disabled) {
            if (event == Status.Event.AgentConnected) {
               allow = false;
            }
        } else if (state == ResourceState.Unmanaged) {
            if (event == Status.Event.AgentConnected) {
                allow = false;
            }
        } else if (state == ResourceState.PrepareForMaintenace) {
            
        } else if (state == ResourceState.Maintenance) {
            
        } else {
            throw new CloudRuntimeException("Unknown resource state " + state);
        }
        
        return allow;
     }
    
    @Override
    public boolean agentStatusTransitTo(HostVO host, Status.Event e, long msId) {
        if (!isAgentEventAllowedByResourceState(host, e)) {
            s_logger.debug(String.format("Cannot proceed agent event %1$s because it is not allowed by current resource state %2$s fort host %3$s", e, host.getResourceState(), host.getId()));
            return false;
        }
        
        host.setManagementServerId(msId);
        try {
            return _statusStateMachine.transitTo(host, e, host.getId(), _hostDao);
        } catch (NoTransitionException e1) {
            s_logger.debug("Cannot transit agent status with event " + e + " for host " + host.getId() + ", mangement server id is " + msId);
            throw new CloudRuntimeException("Cannot transit agent status with event " + e + " for host " + host.getId() + ", mangement server id is " + msId + "," + e1.getMessage());
        }
    }
    
    
    @Override
    public boolean disconnectAgent(HostVO host, Status.Event e, long msId) {
        host.setDisconnectedOn(new Date());
        if (e.equals(Status.Event.Remove)) {
            host.setGuid(null);
            host.setClusterId(null);
        }
        
        return agentStatusTransitTo(host, e, msId);
    }
    
    public void disconnectWithoutInvestigation(AgentAttache attache, final Status.Event event) {
        _executor.submit(new DisconnectTask(attache, event, false));
    }
    
    public void disconnectWithInvestigation(AgentAttache attache, final Status.Event event) {
        _executor.submit(new DisconnectTask(attache, event, true));
    }
    
    private void disconnectInternal(final long hostId, final Status.Event event, boolean invstigate) {
        AgentAttache attache = findAttache(hostId);

        if (attache != null) {
            if (!invstigate) {
            	disconnectWithoutInvestigation(attache, event);
            } else {
                disconnectWithInvestigation(attache, event);
            }
        } else {
            /* Agent is still in connecting process, don't allow to disconnect right away */
            if (tapLoadingAgents(hostId, TapAgentsAction.Contains)) {
                s_logger.info("Host " + hostId + " is being loaded so no disconnects needed.");
                return;
            }

            HostVO host = _hostDao.findById(hostId);
            if (host != null && host.getRemoved() == null) {
               disconnectAgent(host, event, _nodeId);
            }
        }
    }
    
    @Override
    public void disconnect(final long hostId, final Status.Event event) {
        disconnectInternal(hostId, event, false);
    }

	@Override
    public AgentAttache handleDirectConnectAgent(HostVO host, StartupCommand[] cmds, ServerResource resource, boolean forRebalance) throws ConnectionException {
		AgentAttache attache;
		
		attache = createAttacheForDirectConnect(host, resource);
        StartupAnswer[] answers = new StartupAnswer[cmds.length];
        for (int i = 0; i < answers.length; i++) {
            answers[i] = new StartupAnswer(cmds[i], attache.getId(), _pingInterval);
        }
        attache.process(answers);
		attache = notifyMonitorsOfConnection(attache, cmds, forRebalance);
		
		return attache;
    }
	
    @Override
    public void pullAgentToMaintenance(long hostId) {
        AgentAttache attache = findAttache(hostId);
        if (attache != null) {
            attache.setMaintenanceMode(true);
            // Now cancel all of the commands except for the active one.
            attache.cancelAllCommands(Status.Disconnected, false);
        }        
    }
}
