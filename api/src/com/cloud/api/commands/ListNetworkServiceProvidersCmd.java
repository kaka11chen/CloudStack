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

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import com.cloud.api.ApiConstants;
import com.cloud.api.BaseListCmd;
import com.cloud.api.IdentityMapper;
import com.cloud.api.Implementation;
import com.cloud.api.Parameter;
import com.cloud.api.response.ListResponse;
import com.cloud.api.response.ProviderResponse;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.user.Account;


@Implementation(description="Lists network serviceproviders for a given physical network.", responseObject=ProviderResponse.class, since="3.0.0")
public class ListNetworkServiceProvidersCmd extends BaseListCmd {
    public static final Logger s_logger = Logger.getLogger(ListNetworkServiceProvidersCmd.class.getName());
    private static final String _name = "listnetworkserviceprovidersresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    
    @IdentityMapper(entityTableName="physical_network")
    @Parameter(name=ApiConstants.PHYSICAL_NETWORK_ID, type=CommandType.LONG, description="the Physical Network ID")
    private Long physicalNetworkId;
    
    @Parameter(name=ApiConstants.NAME, type=CommandType.STRING, description="list providers by name")
    private String name;
    
    @Parameter(name=ApiConstants.STATE, type=CommandType.STRING, description="list providers by state")
    private String state;
    
    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    
    public void setPhysicalNetworkId(Long physicalNetworkId) {
        this.physicalNetworkId = physicalNetworkId;
    }

    public Long getPhysicalNetworkId() {
        return physicalNetworkId;
    }

    public String getName() {
		return name;
	}

	public String getState() {
		return state;
	}

	/////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////
    @Override
    public String getCommandName() {
        return _name;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }
    
    @Override
    public void execute(){
        List<? extends PhysicalNetworkServiceProvider> serviceProviders = _networkService.listNetworkServiceProviders(getPhysicalNetworkId(), getName(), getState(), this.getStartIndex(), this.getPageSizeVal());
        ListResponse<ProviderResponse> response = new ListResponse<ProviderResponse>();
        List<ProviderResponse> serviceProvidersResponses = new ArrayList<ProviderResponse>();
        for (PhysicalNetworkServiceProvider serviceProvider : serviceProviders) {
            ProviderResponse serviceProviderResponse = _responseGenerator.createNetworkServiceProviderResponse(serviceProvider);
            serviceProvidersResponses.add(serviceProviderResponse);
        }

        response.setResponses(serviceProvidersResponses);
        response.setResponseName(getCommandName());
        this.setResponseObject(response);
    }


}
