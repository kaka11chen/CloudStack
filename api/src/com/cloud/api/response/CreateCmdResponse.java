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

package com.cloud.api.response;

import com.cloud.api.ApiConstants;
import com.cloud.api.IdentityProxy;
import com.google.gson.annotations.SerializedName;

public class CreateCmdResponse extends BaseResponse {
/*	
    @SerializedName(ApiConstants.JOB_ID)
    private Long jobId;
*/

    @SerializedName(ApiConstants.ID)
    private IdentityProxy id = new IdentityProxy();

/*    
    public Long getJobId() {
        return super.getJobId();
    }

    public void setJobId(Long jobId) {
        super.setJobId(jobId);
    }
*/    

    public Long getId() {
        return id.getValue();
    }

    public void setId(Long id) {
        this.id.setValue(id);
    }
    
    public void setIdEntityTable(String entityTable) {
    	this.id.setTableName(entityTable);
    }
}
