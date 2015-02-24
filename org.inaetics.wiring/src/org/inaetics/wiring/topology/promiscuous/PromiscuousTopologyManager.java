/**
 * Licensed under Apache License v2. See LICENSE for more information.
 */
package org.inaetics.wiring.topology.promiscuous;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.inaetics.wiring.ExportRegistration;
import org.inaetics.wiring.ImportReference;
import org.inaetics.wiring.ImportRegistration;
import org.inaetics.wiring.WiringEndpointDescription;
import org.inaetics.wiring.WiringEndpointEvent;
import org.inaetics.wiring.WiringEndpointEventListener;
import org.inaetics.wiring.WiringAdmin;
import org.inaetics.wiring.WiringAdminEvent;
import org.inaetics.wiring.WiringAdminListener;
import org.inaetics.wiring.base.AbstractWiringEndpointPublishingComponent;
import org.inaetics.wiring.endpoint.WiringConstants;
import org.inaetics.wiring.endpoint.WiringEndpoint;
import org.inaetics.wiring.endpoint.WiringEndpointListener;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;

/**
 * {@link PromiscuousTopologyManager} implements a <i>Topology Manager</i> with of a promiscuous strategy. It will import
 * any discovered remote endpoints and export any locally available endpoints.<p>
 * 
 * @author <a href="mailto:amdatu-developers@amdatu.org">Amdatu Project Team</a>
 */
public final class PromiscuousTopologyManager extends AbstractWiringEndpointPublishingComponent implements
    WiringAdminListener, WiringEndpointEventListener, ManagedService {

    public final static String SERVICE_PID = "org.amdatu.remote.topology.promiscuous";

    private final Map<WiringEndpointListener, String> m_exportableEndpointListeners = new HashMap<WiringEndpointListener, String>();
    private final Map<WiringEndpointListener, Map<WiringAdmin, ExportRegistration>> m_exportedEndpointListeners =
            new HashMap<WiringEndpointListener, Map<WiringAdmin, ExportRegistration>>();

    private final Set<WiringEndpointDescription> m_importableEndpoints = new HashSet<WiringEndpointDescription>();
    private final Map<WiringEndpointDescription, Map<WiringAdmin, ImportRegistration>> m_importedEndpoints =
        new HashMap<WiringEndpointDescription, Map<WiringAdmin, ImportRegistration>>();
    private final Map<WiringEndpointDescription, ServiceRegistration<WiringEndpoint>> m_registeredServices =
            new HashMap<WiringEndpointDescription, ServiceRegistration<WiringEndpoint>>();

    private final List<WiringAdmin> m_wiringAdmins = new ArrayList<WiringAdmin>();

	private volatile BundleContext m_context;

    public PromiscuousTopologyManager() {
        super("topology", "promiscuous");
    }

    @Override
    public void updated(Dictionary<String, ?> configuration) throws ConfigurationException {

    	// TODO use filters as in RSA TM ?
    
    }

    // Dependency Manager callback method
    public void wiringAdminAdded(ServiceReference<WiringAdmin> reference, WiringAdmin admin) {
    	m_wiringAdmins.add(admin);
    	exportEndpoints(admin);
    	importEndpoints(admin);
    }
    
    // Dependency Manager callback method
    public void wiringAdminRemoved(ServiceReference<WiringAdmin> reference, WiringAdmin admin) {
    	m_wiringAdmins.remove(admin);
    	unExportEndpoints(admin);
    	unImportEndpoints(admin);
    }
    
    // Dependency Manager callback method
    public void endpointListenerAdded(ServiceReference<WiringEndpointListener> reference, WiringEndpointListener listener) {
    	String name = (String) reference.getProperty(WiringConstants.PROPERTY_ENDPOINT_NAME);
    	if (name == null) {
    		logError("missing name property, will not export %s", listener);
    		return;
    	}
    	exportEndpoints(listener, name);
    }

    // Dependency Manager callback method
    public void endpointListenerRemoved(ServiceReference<WiringEndpointListener> reference, WiringEndpointListener listener) {
    	m_exportableEndpointListeners.remove(listener);
    	unExportEndpoints(listener);
    }
    
	@Override
	public void endpointChanged(WiringEndpointEvent event) {
		switch (event.getType()) {
			case WiringEndpointEvent.ADDED:
				importEndpoint(event.getEndpoint());
				break;
			case WiringEndpointEvent.REMOVED:
				unImportEndpoint(event.getEndpoint());
				break;
			default:
				logError("unknown wiring endpoint event type: %s", event.getType());
		}
	}

	@Override
	public void wiringAdminEvent(WiringAdminEvent event) {
		// TODO
	}

	private void exportEndpoints(WiringAdmin admin) {
    	Set<Entry<WiringEndpointListener, String>> exportableSet = m_exportableEndpointListeners.entrySet();
    	for (Entry<WiringEndpointListener, String> entry : exportableSet) {
    		exportEndpoint(admin, entry.getKey(), entry.getValue());
		}		
	}
	
	private void exportEndpoints(WiringEndpointListener listener, String serviceId) {
		for (WiringAdmin admin : m_wiringAdmins) {
			exportEndpoint(admin, listener, serviceId);
		}
	}

	private void exportEndpoint(WiringAdmin admin, WiringEndpointListener listener, String serviceId) {
		
    	m_exportableEndpointListeners.put(listener, serviceId);

		// export wiring listeners
		ExportRegistration exportRegistration = admin.exportEndpoint(listener, serviceId);
		Map<WiringAdmin, ExportRegistration> adminMap = m_exportedEndpointListeners.get(listener);
		if (adminMap == null) {
			adminMap = new HashMap<WiringAdmin, ExportRegistration>();
			m_exportedEndpointListeners.put(listener, adminMap);
		}
		adminMap.put(admin, exportRegistration);
		
		// notify endpoint listeners
		endpointAdded(exportRegistration.getExportReference().getEndpointDescription());
	}
	
	private void importEndpoints(WiringAdmin admin) {
    	for (WiringEndpointDescription endpointDescription : m_importableEndpoints) {
    		importEndpoint(admin, endpointDescription);
		}
	}

	private void importEndpoint(WiringEndpointDescription endpointDescription) {
		m_importableEndpoints.add(endpointDescription);
		for (WiringAdmin admin : m_wiringAdmins) {
			importEndpoint(admin, endpointDescription);
		}
	}
	
	private void importEndpoint(WiringAdmin admin, WiringEndpointDescription endpointDescription) {
		
		// import endpoints
	    ImportRegistration importRegistration = admin.importEndpoint(endpointDescription);
	    
	    if (importRegistration != null) {
			Map<WiringAdmin, ImportRegistration> adminMap = m_importedEndpoints.get(endpointDescription);
			if (adminMap == null) {
				adminMap = new HashMap<WiringAdmin, ImportRegistration>();
				m_importedEndpoints.put(endpointDescription, adminMap);
			}
			adminMap.put(admin, importRegistration);
			registerService(importRegistration);
	    }
	}
	
	private void registerService(ImportRegistration registration) {

		ImportReference importReference = registration.getImportReference();
		WiringEndpointDescription endpointDescription = importReference.getEndpointDescription();
		WiringEndpoint wiringEndpoint = importReference.getEndpoint();
		
		Dictionary<String, Object> properties = new Hashtable<String, Object>();
        properties.put(WiringConstants.PROPERTY_ZONE_ID, endpointDescription.getZone());
        properties.put(WiringConstants.PROPERTY_NODE_ID, endpointDescription.getNode());
        properties.put(WiringConstants.PROPERTY_ENDPOINT_NAME, endpointDescription.getEndpointName());
        properties.put(WiringConstants.PROPERTY_SECURE, endpointDescription.getProperty(WiringConstants.PROPERTY_SECURE));
    		
        ServiceRegistration<WiringEndpoint> serviceRegistration = m_context.registerService(WiringEndpoint.class, wiringEndpoint, properties);
        m_registeredServices.put(endpointDescription, serviceRegistration);
	}
	
	private void unExportEndpoints(WiringAdmin admin) {
		
		// close and remove registration, notify endpoint listeners
		Collection<Map<WiringAdmin, ExportRegistration>> adminMaps = m_exportedEndpointListeners.values();
		for (Map<WiringAdmin, ExportRegistration> adminMap : adminMaps) {
			ExportRegistration exportRegistration = adminMap.remove(admin);
			if (exportRegistration != null) {
				unExport(exportRegistration);
			}
		}
	}

	private void unExportEndpoints(WiringEndpointListener listener) {
		
		// close and remove registration, notify endpoint listeners
		Map<WiringAdmin, ExportRegistration> adminMap = m_exportedEndpointListeners.remove(listener);
		Collection<ExportRegistration> registrations = adminMap.values();
		for (ExportRegistration registration : registrations) {
			unExport(registration);
		}
	}
	
	private void unExport(ExportRegistration registration) {
		endpointRemoved(registration.getExportReference().getEndpointDescription());
		registration.close();
	}
	
	private void unImportEndpoints(WiringAdmin admin) {
		
		// close and remove registration
		Collection<Map<WiringAdmin, ImportRegistration>> adminMaps = m_importedEndpoints.values();
		for (Map<WiringAdmin, ImportRegistration> adminMap : adminMaps) {
			ImportRegistration importRegistration = adminMap.remove(admin);
			if (importRegistration != null) {
				importRegistration.close();
			}
		}
	}
	
	private void unImportEndpoint(WiringEndpointDescription endpointDescription) {
		m_importableEndpoints.remove(endpointDescription);
		Map<WiringAdmin, ImportRegistration> adminMap = m_importedEndpoints.remove(endpointDescription);
		Collection<ImportRegistration> registrations = adminMap.values();
		for (ImportRegistration registration : registrations) {
			registration.close();
		}
		
		unregisterService(endpointDescription);
	}
	
	private void unregisterService(WiringEndpointDescription endpointDescription) {
		ServiceRegistration<WiringEndpoint> serviceRegistration = m_registeredServices.get(endpointDescription);
		serviceRegistration.unregister();
		m_registeredServices.remove(endpointDescription);
	}
}
