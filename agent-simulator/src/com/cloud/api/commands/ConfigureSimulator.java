// Copyright 2012 Citrix Systems, Inc. Licensed under the
// Apache License, Version 2.0 (the "License"); you may not use this
// file except in compliance with the License.  Citrix Systems, Inc.
// reserves all rights not expressly granted by the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// 
// Automatically generated by addcopyright.py at 04/03/2012
package com.cloud.api.commands;

import org.apache.log4j.Logger;

import com.cloud.agent.manager.SimulatorManager;
import com.cloud.api.ApiConstants;
import com.cloud.api.BaseCmd;
import com.cloud.api.Implementation;
import com.cloud.api.Parameter;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.SuccessResponse;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.server.ManagementService;
import com.cloud.user.Account;
import com.cloud.utils.component.ComponentLocator;

@Implementation(description="configure simulator", responseObject=SuccessResponse.class)
public class ConfigureSimulator extends BaseCmd {
    public static final Logger s_logger = Logger.getLogger(ConfigureSimulator.class.getName());
    private static final String s_name = "configuresimulatorresponse";
    
    @Parameter(name=ApiConstants.ZONE_ID, type=CommandType.LONG, description="configure range: in a zone")
    private Long zoneId;
    
    @Parameter(name=ApiConstants.POD_ID, type=CommandType.LONG, description="configure range: in a pod")
    private Long podId;
    
    @Parameter(name=ApiConstants.CLUSTER_ID, type=CommandType.LONG, description="configure range: in a cluster")
    private Long clusterId;
    
    @Parameter(name=ApiConstants.HOST_ID, type=CommandType.LONG, description="configure range: in a host")
    private Long hostId;
    
    @Parameter(name=ApiConstants.NAME, type=CommandType.STRING, required=true, description="which command needs to be configured")
    private String command;
    
    @Parameter(name=ApiConstants.VALUE, type=CommandType.STRING, required=true, description="configuration options for this command, which is seperated by ;")
    private String values;
    
    @Override
    public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException, ConcurrentOperationException, ResourceAllocationException {
        ComponentLocator locator = ComponentLocator.getLocator(ManagementService.Name);
        SimulatorManager _simMgr = locator.getManager(SimulatorManager.class);
        boolean result = _simMgr.configureSimulator(zoneId, podId, clusterId, hostId, command, values);
        if (!result) {
            throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Failed to configure simulator");
        }
        
        SuccessResponse response = new SuccessResponse(getCommandName());
        this.setResponseObject(response);
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

}
