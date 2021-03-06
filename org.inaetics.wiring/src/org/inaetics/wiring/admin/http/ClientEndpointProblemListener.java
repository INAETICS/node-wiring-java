/**
 * Licensed under Apache License v2. See LICENSE for more information.
 */
package org.inaetics.wiring.admin.http;

/**
 * Provides an abstraction for client endpoints to report problems.
 * 
 * @author <a href="mailto:amdatu-developers@amdatu.org">Amdatu Project Team</a>
 */
public interface ClientEndpointProblemListener {

    void handleEndpointError(Throwable exception);

    void handleEndpointWarning(Throwable exception);
}
