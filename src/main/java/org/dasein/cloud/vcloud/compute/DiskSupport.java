/**
 * Copyright (C) 2009-2014 Dell, Inc
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.vcloud.compute;

import org.apache.log4j.Logger;
import org.dasein.cloud.CloudErrorType;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.GeneralCloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.OperationNotSupportedException;
import org.dasein.cloud.ResourceNotFoundException;
import org.dasein.cloud.ResourceStatus;
import org.dasein.cloud.Tag;
import org.dasein.cloud.compute.AbstractVolumeSupport;
import org.dasein.cloud.compute.Volume;
import org.dasein.cloud.compute.VolumeCreateOptions;
import org.dasein.cloud.compute.VolumeFormat;
import org.dasein.cloud.compute.VolumeState;
import org.dasein.cloud.compute.VolumeType;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.TagUtils;
import org.dasein.cloud.vcloud.vCloud;
import org.dasein.cloud.vcloud.vCloudMethod;
import org.dasein.util.CalendarWrapper;
import org.dasein.util.uom.storage.Gigabyte;
import org.dasein.util.uom.storage.Storage;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Implements support for disks in vCloud 5.1 and beyond.
 * <p>Created by George Reese: 2/10/13 12:10 PM</p>
 * @author George Reese
 */
public class DiskSupport extends AbstractVolumeSupport<vCloud> {
    static private final Logger logger = vCloud.getLogger(DiskSupport.class);

    DiskSupport(@Nonnull vCloud provider) { super(provider); }

    @Override
    public void attach(@Nonnull String volumeId, @Nonnull String toServer, @Nonnull String deviceId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.attachVolume");
        try {
            vCloudMethod method = new vCloudMethod(getProvider());
            StringBuilder xml = new StringBuilder();

            xml.append("<DiskAttachOrDetachParams xmlns=\"http://www.vmware.com/vcloud/v1.5\">");
            xml.append("<Disk type=\"application/vnd.vmware.vcloud.disk+xml\" href=\"").append(method.toURL("disk", volumeId)).append("\" />");
            xml.append("</DiskAttachOrDetachParams>");
            method.waitFor(method.post("attachVolume", method.toURL("vApp", toServer) + "/disk/action/attach", method.getMediaTypeForActionAttachVolume(), xml.toString()));
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull String createVolume(@Nonnull VolumeCreateOptions options) throws InternalException, CloudException {
        if( options.getFormat().equals(VolumeFormat.NFS) ) {
            throw new OperationNotSupportedException("NFS volumes are not currently implemented for " + getProvider().getCloudName());
        }
        if( options.getSnapshotId() != null ) {
            throw new OperationNotSupportedException("Volumes created from snapshots make no sense when there are no snapshots");
        }
        APITrace.begin(getProvider(), "Volume.createVolume");
        try {
            if( !isSubscribed() ) {
                throw new OperationNotSupportedException("This account is not subscribed for creating volume");
            }
            vCloudMethod method = new vCloudMethod(getProvider());
            String vdcId = options.getDataCenterId();

            if( vdcId == null ) {
                vdcId = getProvider().getDataCenterServices().listDataCenters(getContext().getRegionId()).iterator().next().getProviderDataCenterId();
            }
            long size = options.getVolumeSize().convertTo(Storage.BYTE).longValue();
            StringBuilder xml = new StringBuilder();

            xml.append("<DiskCreateParams xmlns=\"http://www.vmware.com/vcloud/v1.5\">");

            xml.append("<Disk name=\"").append(vCloud.escapeXml(options.getName())).append("\" ");
            xml.append("size=\"").append(String.valueOf(size)).append("\">");
            xml.append("<Description>").append(vCloud.escapeXml(options.getDescription())).append("</Description>");
            xml.append("</Disk>");
            xml.append("</DiskCreateParams>");

            String response = method.post(vCloudMethod.CREATE_DISK, vdcId, xml.toString());

            if( response.length() < 1 ) {
                throw new GeneralCloudException("No error, but no volume", CloudErrorType.GENERAL);
            }

            Document doc = method.parseXML(response);
            String docElementTagName = doc.getDocumentElement().getTagName();
            String nsString = "";
            if(docElementTagName.contains(":")) {
                nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
            }
            NodeList disks = doc.getElementsByTagName(nsString + "Disk");

            if( disks.getLength() < 1 ) {
                throw new GeneralCloudException("No error, but no volume", CloudErrorType.GENERAL);
            }
            Node disk = disks.item(0);
            Node href = disk.getAttributes().getNamedItem("href");

            if( href != null ) {
                String volumeId = getProvider().toID(href.getNodeValue().trim());

                try {
                    Map<String,Object> meta = options.getMetaData();

                    if( meta == null ) {
                        meta = new HashMap<String, Object>();
                    }
                    meta.put("dsnCreated", System.currentTimeMillis());
                    meta.put("dsnDeviceId", options.getDeviceId());
                    method.postMetaData("disk", volumeId, meta);
                }
                catch( Throwable ignore ) {
                    logger.warn("Error updating meta-data on volume creation: " + ignore.getMessage());
                }
                String vmId = options.getVlanId();

                if( vmId != null ) {
                    long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE*10L);

                    while( timeout > System.currentTimeMillis() ) {
                        try { Thread.sleep(15000L); }
                        catch( InterruptedException ignore ) { }
                        try {
                            Volume v = getVolume(volumeId);

                            if( v != null && v.getCurrentState().equals(VolumeState.AVAILABLE) ) {
                                break;
                            }
                        }
                        catch( Throwable ignore ) {
                            // ignore
                        }
                    }
                    try { attach(volumeId, vmId, options.getDeviceId()); }
                    catch( Throwable ignore ) { }
                }
                return volumeId;
            }
            throw new GeneralCloudException("No ID provided in Disk XML", CloudErrorType.GENERAL);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void detach(@Nonnull String volumeId, boolean force) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.detach");
        try {
            Volume volume = getVolume(volumeId);

            if( volume == null ) {
                throw new ResourceNotFoundException("Volume", volumeId);
            }
            String serverId = volume.getProviderVirtualMachineId();

            if( serverId == null ) {
                //todo
                //should we have a new exception for errors caused by user/client provided data?
                throw new InternalException("No virtual machine is attached to this volume");
            }
            vCloudMethod method = new vCloudMethod(getProvider());
            StringBuilder xml = new StringBuilder();

            xml.append("<DiskAttachOrDetachParams xmlns=\"http://www.vmware.com/vcloud/v1.5\">");
            xml.append("<Disk href=\"").append(method.toURL("disk", volumeId)).append("\" />");
            xml.append("</DiskAttachOrDetachParams>");
            method.waitFor(method.post("detachVolume",  method.toURL("vApp", serverId) + "/disk/action/detach", method.getMediaTypeForActionAttachVolume(), xml.toString()));
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull DiskCapabilities getCapabilities() {
        return new DiskCapabilities(getProvider());
    }

    @Override
    public Volume getVolume(@Nonnull String volumeId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.getVolume");
        try {
            for( Volume v : listVolumes() ) {
                if( v.getProviderVolumeId().equals(volumeId) ) {
                    return v;
                }
            }
            return null; // TODO: optimize
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<ResourceStatus> listVolumeStatus() throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Volume.listVolumeStatus");
        try {
            return super.listVolumeStatus(); // TODO: optimize
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<Volume> listVolumes() throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.listVolumes");
        try {
            vCloudMethod method = new vCloudMethod(getProvider());
            ArrayList<Volume> volumes = new ArrayList<Volume>();

            for( DataCenter dc : method.listDataCenters() ) {
                String xml = method.get("vdc", dc.getProviderDataCenterId());

                if( xml != null && !xml.equals("") ) {
                    Document doc = method.parseXML(xml);
                    String docElementTagName = doc.getDocumentElement().getTagName();
                    String nsString = "";
                    if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
                    NodeList vdcs = doc.getElementsByTagName(nsString + "Vdc");

                    if( vdcs.getLength() > 0 ) {
                        NodeList attributes = vdcs.item(0).getChildNodes();

                        for( int i=0; i<attributes.getLength(); i++ ) {
                            Node attribute = attributes.item(i);
                            if(attribute.getNodeName().contains(":"))nsString = attribute.getNodeName().substring(0, attribute.getNodeName().indexOf(":") + 1);
                            else nsString = "";

                            if( attribute.getNodeName().equalsIgnoreCase(nsString + "ResourceEntities") && attribute.hasChildNodes() ) {
                                NodeList resources = attribute.getChildNodes();

                                for( int j=0; j<resources.getLength(); j++ ) {
                                    Node resource = resources.item(j);
                                    if(resource.getNodeName().contains(":"))nsString = resource.getNodeName().substring(0, resource.getNodeName().indexOf(":") + 1);
                                    else nsString = "";

                                    if( resource.getNodeName().equalsIgnoreCase(nsString + "ResourceEntity") && resource.hasAttributes() ) {
                                        Node type = resource.getAttributes().getNamedItem("type");

                                        if( type != null && type.getNodeValue().equals(method.getMediaTypeForDisk()) ) {
                                            Node href = resource.getAttributes().getNamedItem("href");
                                            Volume volume = toVolume(dc.getProviderDataCenterId(), getProvider().toID(href.getNodeValue().trim()));

                                            if( volume != null ) {
                                                volumes.add(volume);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return volumes;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Volume.isSubscribed");
        try {
            if( getProvider().testContext() != null ) {
                vCloudMethod method = new vCloudMethod(getProvider());


                return vCloudMethod.matches(method.getAPIVersion(), "5.1", null);
            }
            return false;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void remove(@Nonnull String volumeId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "Volume.remove");
        try {
            vCloudMethod method = new vCloudMethod(getProvider());

            method.delete("disk", volumeId);
        }
        finally {
            APITrace.end();
        }
    }

    private @Nonnull VolumeState toState(@Nonnull String status) {
        if( status.equals("1") ) {
            return VolumeState.AVAILABLE;
        }
        else if( status.equals("0") ) {
            return VolumeState.PENDING;
        }
        return VolumeState.PENDING;
    }

    private @Nullable Volume toVolume(@Nonnull String dcId, @Nonnull String volumeId) throws CloudException, InternalException {
        vCloudMethod method = new vCloudMethod(getProvider());
        Volume volume = new Volume();

        volume.setProviderVolumeId(volumeId);
        volume.setCurrentState(VolumeState.AVAILABLE);
        volume.setFormat(VolumeFormat.BLOCK);
        volume.setType(VolumeType.HDD);
        volume.setProviderRegionId(getContext().getRegionId());
        volume.setProviderDataCenterId(dcId);
        volume.setRootVolume(false);

        String xml = method.get("disk", volumeId);

        if( xml == null || xml.length() < 1 ) {
            return null;
        }
        Document doc = method.parseXML(xml);
        String docElementTagName = doc.getDocumentElement().getTagName();
        String nsString = "";
        if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
        NodeList disks = doc.getElementsByTagName(nsString + "Disk");

        if( disks.getLength() < 1 ) {
            return null;
        }
        Node diskNode = disks.item(0);
        Node n = diskNode.getAttributes().getNamedItem("name");

        if( n != null ) {
            volume.setName(n.getNodeValue().trim());
        }
        n = diskNode.getAttributes().getNamedItem("size");
        if( n != null ) {
            try {
                volume.setSize(new Storage<org.dasein.util.uom.storage.Byte>(Integer.parseInt(n.getNodeValue().trim()), Storage.BYTE));
            }
            catch( NumberFormatException ignore ) {
                // ignore
            }
        }
        if( volume.getSize() == null ) {
            volume.setSize(new Storage<Gigabyte>(1, Storage.GIGABYTE));
        }
        n = diskNode.getAttributes().getNamedItem("status");
        if( n != null ) {
            volume.setCurrentState(toState(n.getNodeValue().trim()));
        }
        NodeList attributes = diskNode.getChildNodes();

        for( int i=0; i<attributes.getLength(); i++ ) {
            Node attribute = attributes.item(i);

            if( attribute.getNodeName().equalsIgnoreCase(nsString + "Description") && attribute.hasChildNodes() ) {
                volume.setDescription(attribute.getFirstChild().getNodeValue().trim());
            }
        }
        try {
            xml = method.get("disk", volumeId + "/metadata");

            if( xml != null && !xml.equals("") ) {
                if( xml != null && !xml.equals("") ) {
                    method.parseMetaData(volume, xml);

                    String t = volume.getTag("dsnCreated");

                    if( t != null ) {
                        try { volume.setCreationTimestamp(Long.parseLong(t)); }
                        catch( Throwable ignore ) { }
                    }
                    t = volume.getTag("dsnDeviceId");
                    if( t != null ) {
                        volume.setDeviceId(t);
                    }
                }
            }
        }
        catch( Throwable ignore ) {
            // ignore
        }
        try {
            xml = method.get("disk", volumeId + "/attachedVms");

            if( xml != null && !xml.equals("") ) {
                doc = method.parseXML(xml);
                docElementTagName = doc.getDocumentElement().getTagName();
                nsString = "";
                if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
                NodeList vms = doc.getElementsByTagName(nsString + "VmReference");

                if( vms.getLength() > 0 ) {
                    Node vm = vms.item(0);
                    Node href = vm.getAttributes().getNamedItem("href");

                    if( href != null ) {
                        volume.setProviderVirtualMachineId(getProvider().toID(href.getNodeValue().trim()));
                    }
                }
            }
        }
        catch( Throwable ignore ) {
            // ignore
        }
        if( volume.getName() == null ) {
            volume.setName(volume.getProviderVolumeId());
        }
        if( volume.getDescription() == null ) {
            volume.setDescription(volume.getName());
        }
        return volume;
    }
    
    @Override
    public void setTags(@Nonnull String volumeId, @Nonnull Tag... tags) throws CloudException, InternalException {
    	APITrace.begin(getProvider(), "Volume.setTags");
    	try {
    		Tag[] collectionForDelete = TagUtils.getTagsForDelete(getVolume(volumeId).getTags(), tags);
    		if (collectionForDelete.length != 0 ) {
    			removeTags(volumeId, collectionForDelete);
    		}
    		Map<String,Object> metadata = new HashMap<String, Object>();
    		vCloudMethod method = new vCloudMethod(getProvider());
    		for( Tag tag : tags ) {
    			metadata.put(tag.getKey(), tag.getValue());
    		}
    		method.postMetaData("disk", volumeId, metadata);
    	}
    	finally {
    		APITrace.end();
    	}
    }
    
    @Override
    public void setTags(@Nonnull String[] volumeIds, @Nonnull Tag... tags) throws CloudException, InternalException {
    	for (String id : volumeIds) {
    		setTags(id, tags);
    	}
    }
    
    @Override
    public void updateTags( @Nonnull String volumeId, @Nonnull Tag... tags ) throws CloudException, InternalException {
    	APITrace.begin(getProvider(), "Volume.updateTags");
    	try {
    		Map<String,Object> metadata = new HashMap<String, Object>();
    		vCloudMethod method = new vCloudMethod(getProvider());
    		for( Tag tag : tags ) {
    			metadata.put(tag.getKey(), tag.getValue());
    		}
    		method.putMetaData("disk", volumeId, metadata);
    	}
    	finally {
    		APITrace.end();
    	}
    }
    
    @Override
    public void updateTags( @Nonnull String[] volumeIds, @Nonnull Tag... tags ) throws CloudException, InternalException {
    	for( String id : volumeIds ) {
    		updateTags(id, tags);
    	}
    }
    
    @Override
    public void removeTags( @Nonnull String volumeId, @Nonnull Tag... tags ) throws CloudException, InternalException {
    	APITrace.begin(getProvider(), "Volume.removeTags");
    	try {
    		Map<String,Object> metadata = new HashMap<String, Object>();
    		vCloudMethod method = new vCloudMethod(getProvider());
    		for( Tag tag : tags ) {
    			metadata.put(tag.getKey(), tag.getValue());
    		}
    		method.delMetaData("disk", volumeId, metadata);
    	}
    	finally {
    		APITrace.end();
    	}
    }
    
    @Override
    public void removeTags( @Nonnull String[] volumeIds, @Nonnull Tag... tags ) throws CloudException, InternalException {
    	for( String id : volumeIds ) {
    		removeTags(id, tags);
    	}
    }
}
