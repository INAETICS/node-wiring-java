/**
 * Licensed under Apache License v2. See LICENSE for more information.
 */
package org.inaetics.wiring.discovery.etcd;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import mousio.client.promises.ResponsePromise;
import mousio.client.promises.ResponsePromise.IsSimplePromiseResponseHandler;
import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.requests.EtcdKeyPutRequest;
import mousio.etcd4j.responses.EtcdException;
import mousio.etcd4j.responses.EtcdKeyAction;
import mousio.etcd4j.responses.EtcdKeysResponse;
import mousio.etcd4j.responses.EtcdKeysResponse.EtcdNode;

import org.inaetics.wiring.WiringEndpointDescription;
import org.inaetics.wiring.discovery.AbstractDiscovery;

/**
 * Etcd implementation of service node based discovery.
 * 
 * @author <a href="mailto:amdatu-developers@amdatu.org">Amdatu Project Team</a>
 */
public final class EtcdNodeDiscovery extends AbstractDiscovery {

	public static final String DISCOVERY_NAME = "Amdatu Wiring Node Discovery (Etcd)";
    public static final String DISCOVERY_TYPE = "etcd";

    private static final String ENDPOINT_KEY_ENDPOINT_COMPLETE = "complete";
    private static final String SEP = "/";

    private final EtcdDiscoveryConfiguration m_configuration;

    private volatile ScheduledExecutorService m_executor;
    private volatile ResponseListener m_responseListener;

    private volatile EtcdRegistrationUpdater m_updater;
    private volatile EtcdClient m_etcd;
    
    private final WiringEndpointDescription m_localEndpoint = new WiringEndpointDescription();
    
    private final Map<String, WiringEndpointDescription> m_publishedEndpoints = new HashMap<String, WiringEndpointDescription>();
    private final ReentrantReadWriteLock m_lock = new ReentrantReadWriteLock();
    
    public EtcdNodeDiscovery(EtcdDiscoveryConfiguration configuration) {
        super(DISCOVERY_TYPE, configuration);
        m_configuration = configuration;
    }

    @Override
    protected void startComponent() throws Exception {
        super.startComponent();

        m_executor = Executors.newSingleThreadScheduledExecutor();
        m_responseListener = new ResponseListener();

        logDebug("Connecting to %s", m_configuration.getConnectUrl());
        m_etcd = new EtcdClient(URI.create(m_configuration.getConnectUrl()));
        logDebug("Etcd version is %s", m_etcd.getVersion());
        m_updater = new EtcdRegistrationUpdater();

        // set local node properties (without enpoints)
    	m_localEndpoint.setZone(m_configuration.getZone());
    	m_localEndpoint.setNode(m_configuration.getNode());

        discoverEndpoints();
    }

    @Override
    protected void stopComponent() throws Exception {

        try {
            m_updater.cancel();
        }
        catch (Exception e) {
            logError("cancel updater failed", e);
        }

        try {
            m_etcd.close();
        }
        catch (Exception e) {
            logError("closing etcd client failed", e);
        }

        m_executor.shutdown();
        m_executor = null;

        super.stopComponent();
    }

    private void discoverEndpoints() throws Exception {
        long index = 0l;
        try {
        	
        	// create dirs if not available yet...
        	String rootPath = m_configuration.getRootPath();
        	
        	try {
				m_etcd.putDir(rootPath).send().get();
			} catch (Exception e) {
				// nothing to do, directory exists already
			}
        	
            EtcdKeysResponse response = m_etcd.getDir(rootPath).recursive().send().get();
            index = getEtcdIndex(response);
            logDebug("discovering endpoints at etcd index %s", index);
            
        	try {
	            if (response.node.dir && response.node.nodes != null) {
	                List<WiringEndpointDescription> nodes = getWiringEndpointDescriptions(response);
	                setDiscoveredEndpoints(nodes);
	            }
        	}
        	catch (Exception e) {
				logWarning("Failed to set discovered endpoint(s)", e);
			}
            
        }
        catch (EtcdException e) {
            logError("Could not discovery endpoints!", e);
        }
        finally {
            setDirectoryWatch(index + 1);
        }
    }

    private List<WiringEndpointDescription> getWiringEndpointDescriptions(EtcdKeysResponse response) {
    	
    	List<WiringEndpointDescription> endpoints = new ArrayList<>();
    	
        // zones
    	for (EtcdNode zoneNode : response.node.nodes) {
    		if(zoneNode.dir && zoneNode.nodes != null) {
    			String zone = getLastPart(zoneNode.key);
    			
    			// nodes
            	for (EtcdNode nodeNode : zoneNode.nodes) {
            		if(nodeNode.dir && nodeNode.nodes != null) {
            			String node = getLastPart(nodeNode.key);

            			// path
                    	for (EtcdNode pathNode : nodeNode.nodes) {
                    		if(pathNode.dir && pathNode.nodes != null) {
                    			String path = getLastPart(pathNode.key);

                    			// protocol
                            	for (EtcdNode protocolNode : pathNode.nodes) {
                            		if(protocolNode.dir && protocolNode.nodes != null) {
	                        			String protocol = getLastPart(protocolNode.key);
                            			
                                		Map<String, String> properties = new HashMap<String, String>();
                            			String complete = Boolean.FALSE.toString();
                            			
                            			// endpoint properties
	                                	for (EtcdNode propertyNode : protocolNode.nodes) {
	                                		
	                                		String propertyKey = getLastPart(propertyNode.key);
	                                		if (!propertyKey.equals(ENDPOINT_KEY_ENDPOINT_COMPLETE)) {
	                                			properties.put(propertyKey, propertyNode.value);
	                                		}
	                                		else {
	                                			complete = propertyNode.value;
	                                		}
	                                	}

	                                	if (complete.equals(Boolean.TRUE.toString())) {
	                                		logDebug("Adding %s %s %s %s", zone, node, path, protocol);
	                                		
	                                		WiringEndpointDescription endpointDescription = new WiringEndpointDescription();
	                                		endpointDescription.setZone(zone);
	                                		endpointDescription.setNode(node);
	                                		endpointDescription.setEndpointName(path);
	                                		String[] protocolParts = splitProtocol(protocol);
	                                		endpointDescription.setProtocolName(protocolParts[0]);
	                                		endpointDescription.setProtocolVersion(protocolParts[1]);
	                                		endpointDescription.setProperties(properties);
	                                		
	                                		endpoints.add(endpointDescription);
	                                	}
                            				
                            		}
                            	}
                    		}
                    	}
            		}
            	}
    		}
    	}
    	
    	return endpoints;
    }
    
    private String getLastPart(String s) {
    	if (!s.contains(SEP)) {
    		return s;
    	}
    	return s.substring(s.lastIndexOf(SEP) + 1);
    }
    
    private void handleDiscoveryNodeChange(EtcdKeysResponse response) throws Exception {

    	long index = 0l;
        try {
            index = response.node.modifiedIndex;
            logInfo("Handling endpoint change at etcd index %s, action %s, key %s", index, response.action.toString(), response.node.key);
            
            // new node is ready on a set on the "complete" key with value "true"
            if (response.action == EtcdKeyAction.set
            		&& response.node.key.endsWith(SEP + ENDPOINT_KEY_ENDPOINT_COMPLETE)
            		&& response.node.value.equals(Boolean.TRUE.toString())) {

            	WiringEndpointDescription endpoint = getEndpointFromKey(response.node.key, true);
                addDiscoveredEndpoint(endpoint);

            }
            
            // remove node on "delete" or "expire"
            else if ((response.action == EtcdKeyAction.delete || response.action == EtcdKeyAction.expire)) {

            	WiringEndpointDescription endpoint = getEndpointFromKey(response.node.key, false);
                removeDiscoveredEndpoint(endpoint);

            }
        }
        catch (Exception e) {
            logError("Could not handle endpoint change!", e);
        }
        finally {
            setDirectoryWatch(index + 1);
        }
    }
    
    private WiringEndpointDescription getEndpointFromKey(String key, boolean doGetEndpointProperties) {

    	String all = key.substring(m_configuration.getRootPath().length());
    	if (all.startsWith(SEP)) {
    		all = all.substring(1);
    	}
    	
    	WiringEndpointDescription endpoint = new WiringEndpointDescription();
    	
    	// zone
    	String zone = getNextPart(all); 
    	endpoint.setZone(zone);
    	all = all.substring(zone.length() + 1);

    	// node
    	String node = getNextPart(all); 
    	endpoint.setNode(node);
    	all = all.substring(node.length() + 1);
    	
    	// path
    	String path = getNextPart(all); 
    	endpoint.setEndpointName(path);
    	all = all.substring(path.length() + 1);
    	
    	// protocol
    	String protocol = getNextPart(all);
    	// format is currently "protocolName;version=protocolVersion"
    	String[] protocolParts = protocol.split(";");
    	endpoint.setProtocolName(protocolParts[0]);
    	endpoint.setProtocolVersion(protocolParts[1].substring(protocolParts[1].indexOf("=") + 1));
    	all = all.substring(protocol.length() + 1);

    	if(doGetEndpointProperties) {

    		// get other values from etcd
    		try {
    			
    			String endpointKey = key.substring(0, key.lastIndexOf(SEP));
    			EtcdNode endpointNode = m_etcd.get(endpointKey).recursive().send().get().node;
    			
    			// set properties
    			for (EtcdNode propertyNode : endpointNode.nodes) {
            		String propertyKey = getLastPart(propertyNode.key);
            		if (!propertyKey.equals(ENDPOINT_KEY_ENDPOINT_COMPLETE)) {
            			endpoint.setProperty(propertyKey, propertyNode.value);
            		}
            	}
    			
    		} catch (Exception e) {
    			logError("error getting endpoint properties", e);
    		}
    		
    	}

    	return endpoint;
    }
    
    private String getNextPart(String s) {
    	return s.contains(SEP) ? s.substring(0, s.indexOf(SEP)) : null;
    }

    private String[] splitProtocol(String protocol) {
    	String[] result = new String[2];
    	String[] protocolParts = protocol.split(";");
    	result[0] = protocolParts[0];
    	result[1] = protocolParts[1].substring(protocolParts[1].indexOf("=") + 1);
    	return result;
    }
    
    private long getEtcdIndex(EtcdKeysResponse response) {

        long index = 0l;
        if (response != null) {
            // get etcdIndex with fallback to modifiedIndex
            // see https://github.com/coreos/etcd/pull/1082#issuecomment-56444616
            if (response.etcdIndex != null) {
                index = response.etcdIndex;
            }
            else if (response.node.modifiedIndex != null) {
                index = response.node.modifiedIndex;
            }
            // potential bug fallback
            // see https://groups.google.com/forum/?hl=en#!topic/etcd-dev/S12405PCKaU
            if (response.node.dir && response.node.nodes != null) {
                for (EtcdNode node : response.node.nodes) {
                    if (node.modifiedIndex > index) {
                        index = node.modifiedIndex;
                    }
                }
            }
        }
        return index;
    }

    private void setDirectoryWatch(long index) {

        logDebug("Setting watch for index %s", index);
        try {
            m_etcd.get(m_configuration.getRootPath())
                .waitForChange((int) index)
                .recursive()
                .send()
                .addListener(m_responseListener);
        }
        catch (IOException e) {
            // TODO How do we recover from this?
            logError("Failed to set new watch on discovery directory!", e);
        }
    }

    private String getRootPath() {
    	String rootPath = m_configuration.getRootPath();
    	if (!rootPath.endsWith("/")) {
    		rootPath += "/";
    	}
    	return rootPath;
    }

    private String getZonePath(WiringEndpointDescription endpoint) {
    	return getRootPath() + endpoint.getZone() + "/";
    }
    
    private String getNodePath(WiringEndpointDescription endpoint) {
    	return getZonePath(endpoint) + endpoint.getNode() + "/";
    }

    private String getPathPath(WiringEndpointDescription endpoint) {
    	return getNodePath(endpoint) + endpoint.getEndpointName() + "/";
    }

    private String getProtocolPath(WiringEndpointDescription endpoint) {
    	return getPathPath(endpoint) + endpoint.getProtocolName() + ";version=" + endpoint.getProtocolVersion() + "/";
    }
    
    private class EtcdRegistrationUpdater implements Runnable {

        private static final int ETCD_REGISTRATION_TTL = 60;

        private final ScheduledFuture<?> m_future;

        public EtcdRegistrationUpdater() throws Exception {
            putPublishedEndpoints();
            m_future =
                m_executor.scheduleAtFixedRate(this, ETCD_REGISTRATION_TTL - 5, ETCD_REGISTRATION_TTL - 5,
                    TimeUnit.SECONDS);
        }

        private void putPublishedEndpoints() throws Exception {

        	m_lock.readLock().lock();
        	
        	for (WiringEndpointDescription endpoint : m_publishedEndpoints.values()) {
        		putPublishedEndpoint(endpoint);
        	}
        	
        	m_lock.readLock().unlock();
        }

		public void putPublishedEndpoint(WiringEndpointDescription endpoint) throws Exception {
			
			// put protocol
			putDir(getProtocolPath(endpoint));

        	// put endpoint properties
			Map<String, String> properties = endpoint.getProperties();
			Set<String> keys = properties.keySet();
			for (String key : keys) {
	        	m_etcd.put(getProtocolPath(endpoint) + key, properties.get(key)).send().get();
			}
        	
        	// put marker that everything is written
        	m_etcd.put(getProtocolPath(endpoint) + ENDPOINT_KEY_ENDPOINT_COMPLETE, Boolean.TRUE.toString()).send().get();
        }
        
        private void putDir(String path) throws Exception {
        	EtcdKeyPutRequest putRequest = m_etcd.putDir(path).ttl(ETCD_REGISTRATION_TTL);
        	// putDir with ttl needs prevExist when already existing
        	if(dirExists(path)) {
        		putRequest.prevExist(true);
        	}
        	putRequest.send().get();
        }

        private boolean dirExists(String path) {
        	try {
				m_etcd.getDir(path).send().get();
			} catch (Exception e) {
				return false;
			}
        	return true;
        }

		@Override
        public void run() {
            try {
            	putPublishedEndpoints();
            }
            catch (Exception e) {
                logError("Etcd registration update failed", e);
            }
        }

        public void cancel() {
            try {
                m_future.cancel(false);
                deleteLocalEndpoints();
            }
            catch (Exception e) {
                logError("Etcd deregistration update failed", e);
            }
        }
        
        private void deleteLocalEndpoints() throws Exception {
        	m_lock.readLock().lock();
        	for (WiringEndpointDescription endpoint : m_publishedEndpoints.values()) {
        		deleteEndpoint(endpoint);
        	}
        	m_lock.readLock().unlock();
        }
        
        public void deleteEndpoint(WiringEndpointDescription endpoint) throws Exception {
        	m_etcd.deleteDir(getProtocolPath(endpoint)).recursive().send();
        }
        
        
    }

    private class ResponseListener implements IsSimplePromiseResponseHandler<EtcdKeysResponse> {

		@Override
		public void onResponse(ResponsePromise<EtcdKeysResponse> promise) {
			try {
				if (promise.getException() != null) {
					logWarning("etcd watch received exception: %s", promise.getException().getMessage());
					discoverEndpoints();
					return;
				}
				handleDiscoveryNodeChange(promise.get());
			} catch (Exception e) {
				logWarning("Could not get node(s)", e);
			}
		}
    }

	@Override
	protected void addPublishedEndpoint(WiringEndpointDescription endpoint) {
		m_lock.writeLock().lock();
		m_publishedEndpoints.put(endpoint.getId(), endpoint);
		try {
			m_updater.putPublishedEndpoint(endpoint);
		} catch (Exception e) {
			logError("error publishing endpoint %s", e, endpoint);
		}
		m_lock.writeLock().unlock();
	}

	@Override
	protected void removePublishedEndpoint(WiringEndpointDescription endpoint) {
		m_lock.writeLock().lock();
		m_publishedEndpoints.remove(endpoint.getId());
		try {
			m_updater.deleteEndpoint(endpoint);
		} catch (Exception e) {
			logError("error unpublishing endpoint %s", e, endpoint);
		}
		m_lock.writeLock().unlock();
	}

}
