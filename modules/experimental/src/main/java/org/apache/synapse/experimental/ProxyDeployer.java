/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.experimental;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMException;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.axiom.om.util.StAXUtils;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.deployment.AbstractDeployer;
import org.apache.axis2.deployment.DeploymentException;
import org.apache.axis2.deployment.repository.util.DeploymentFileData;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.config.xml.ProxyServiceFactory;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.ProxyService;

import javax.xml.stream.XMLStreamException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class ProxyDeployer extends AbstractDeployer {
    private final Map<String,String> filenameToProxyNameMap = new HashMap<String,String>();
    private ConfigurationContext cfgCtx = null;

    public void init(ConfigurationContext configurationContext) {
        this.cfgCtx = configurationContext;
    }

    private SynapseConfiguration getSynapseConfiguration() throws DeploymentException {
        Parameter synCfgParam =
                cfgCtx.getAxisConfiguration().getParameter(SynapseConstants.SYNAPSE_CONFIG);
        if (synCfgParam == null) {
            throw new DeploymentException("SynapseConfiguration not found. " +
                    "Are you sure that you are running Synapse?");
        }
        return (SynapseConfiguration) synCfgParam.getValue();
    }

    private SynapseEnvironment getSynapseEnvironment() throws DeploymentException {
        Parameter synCfgParam =
                cfgCtx.getAxisConfiguration().getParameter(SynapseConstants.SYNAPSE_ENV);
        if (synCfgParam == null) {
            throw new DeploymentException("SynapseEnvironment not found. " +
                    "Are you sure that you are running Synapse?");
        }
        return (SynapseEnvironment) synCfgParam.getValue();
    }
    
    public void deploy(DeploymentFileData deploymentFileData) throws DeploymentException {
        String filename = deploymentFileData.getAbsolutePath();
        ProxyService proxy;
        try {
            InputStream in = new FileInputStream(filename);
            try {
                OMElement element = new StAXOMBuilder(StAXUtils.createXMLStreamReader(in)).getDocumentElement();
                proxy = ProxyServiceFactory.createProxy(element);
            } finally {
                in.close();
            }
        } catch (IOException ex) {
            throw new DeploymentException("Error reading " + filename + ": " + ex.getMessage(), ex);
        } catch (XMLStreamException ex) {
            throw new DeploymentException("Error parsing " + filename + ": " + ex.getMessage(), ex);
        } catch (OMException ex) {
            throw new DeploymentException("Error parsing " + filename + ": " + ex.getMessage(), ex);
        }
        AxisConfiguration axisCfg = cfgCtx.getAxisConfiguration();
        SynapseConfiguration synCfg = getSynapseConfiguration();
        SynapseEnvironment synapseEnvironment = getSynapseEnvironment();
        filenameToProxyNameMap.put(filename, proxy.getName());
        // Copy from SynapseXMLConfigurationFactory#defineProxy
        synCfg.addProxyService(proxy.getName(), proxy);
        // Copy from SynapseInitializationModule#init (TODO: imcomplete: doesn't take pinnedServers into account)
        proxy.buildAxisService(synCfg, axisCfg);
        // Copy from SynapseConfiguration#init
        if (proxy.getTargetInLineEndpoint() instanceof ManagedLifecycle) {
            ((ManagedLifecycle) proxy.getTargetInLineEndpoint()).init(synapseEnvironment);
        }

        if (proxy.getTargetInLineInSequence() != null) {
            proxy.getTargetInLineInSequence().init(synapseEnvironment);
        }

        if (proxy.getTargetInLineOutSequence() != null) {
            proxy.getTargetInLineOutSequence().init(synapseEnvironment);
        }

        if (proxy.getTargetInLineFaultSequence() != null) {
            proxy.getTargetInLineFaultSequence().init(synapseEnvironment);
        }
    }

    public void setDirectory(String directory) {
    }

    public void setExtension(String extension) {
    }

    public void undeploy(String fileName) throws DeploymentException {
        String proxyName = filenameToProxyNameMap.get(fileName);
        if (proxyName == null) {
            throw new DeploymentException("Nothing known about file '" + fileName + "'");
        } else {
            getSynapseConfiguration().removeProxyService(proxyName);
            filenameToProxyNameMap.remove(fileName);
        }
    }
}
