package org.inaetics.wiring.admin.http;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.inaetics.wiring.AbstractComponentDelegate;
import org.inaetics.wiring.admin.WiringAdminListener;
import org.inaetics.wiring.admin.WiringConstants;
import org.inaetics.wiring.nodeEndpoint.NodeEndpointDescription;
import org.osgi.framework.ServiceReference;

public class WiringAdminListenerHandler extends AbstractComponentDelegate {

	private final Map<WiringAdminListener, NodeEndpointDescription> m_wiringAdminListeners =
			new ConcurrentHashMap<WiringAdminListener, NodeEndpointDescription>();

    private final WiringAdminFactory m_manager;
    private final HttpAdminConfiguration m_configuration;
    private final HttpServerEndpointHandler m_serverEndpointHandler;
    
	public WiringAdminListenerHandler(WiringAdminFactory wiringAdminFactory, HttpAdminConfiguration configuration, HttpServerEndpointHandler serverEndpointHandler) {
		super (wiringAdminFactory);
		m_manager = wiringAdminFactory;
        m_configuration = configuration;
        m_serverEndpointHandler = serverEndpointHandler;
	}
	
	// Dependency Manager callback method
	protected final void wiringAdminListenerAdded(ServiceReference<?> reference, WiringAdminListener listener) {

		logDebug("Adding WiringAdminListener %s", reference);

		String path = getPath(reference);
		if (path == null) {
			logError("Adding WiringAdminListener failed, no path property found %s", reference);
			return;
		}
		
		// create new endpoint
		NodeEndpointDescription endpoint = new NodeEndpointDescription();
		endpoint.setZone(m_configuration.getZone());
		endpoint.setNode(m_configuration.getNode());
		endpoint.setPath(path);
		endpoint.setProtocol(HttpAdminConstants.PROTOCOL);
		try {
			endpoint.setUrl(new URL(m_configuration.getBaseUrl().toString() + path));
		} catch (MalformedURLException e) {
			logError("error creating endpoint url", e);
		}
		
		// create http handler
		m_serverEndpointHandler.addEndpoint(endpoint, listener);
		
		// emit event
		m_manager.nodeAdded(endpoint);

		m_wiringAdminListeners.put(listener, endpoint);

		logDebug("WiringAdminListener added %s", reference);

	}

	// Dependency Manager callback method
	protected final void wiringAdminListenerRemoved(ServiceReference<?> reference, WiringAdminListener listener) {
		
		logDebug("Removing WiringAdminListener %s", reference);

		// remove http handler
		NodeEndpointDescription endpoint = m_wiringAdminListeners.get(listener);
		m_serverEndpointHandler.removeEndpoint(endpoint);
		
		// emit event
		m_manager.nodeRemoved(endpoint);

		m_wiringAdminListeners.remove(listener);
		
		logDebug("WiringAdminListener removed %s", reference);
	}

	private String getPath(ServiceReference<?> reference) {
		Object path = reference.getProperty(WiringConstants.PROPERTY_PATH);
		if (path != null) {
			return path.toString();
		}
		return null;
	}

}