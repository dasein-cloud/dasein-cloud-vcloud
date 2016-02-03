package org.dasein.cloud.vcloud;

import mockit.Mocked;
import mockit.NonStrictExpectations;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.compute.Architecture;
import org.dasein.cloud.compute.ImageClass;
import org.dasein.cloud.compute.ImageFilterOptions;
import org.dasein.cloud.compute.MachineImage;
import org.dasein.cloud.compute.MachineImageState;
import org.dasein.cloud.compute.Platform;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vcloud.compute.TemplateSupport;
import org.dasein.util.uom.time.Minute;
import org.dasein.util.uom.time.TimePeriod;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * User: daniellemayne
 * Date: 07/01/2016
 * Time: 08:45
 */
public class TemplateTest {
    @Mocked
    ProviderContext providerContextMock;
    @Mocked
    vCloud vcloudMock;

    private TemplateSupport templateSupport;
    private Cache<MachineImage> imgCache = null;

    private Iterable<MachineImage> getTestTemplateList() {
        List<MachineImage> list = new ArrayList<MachineImage>();

        MachineImage img = MachineImage.getInstance("OWNER", "REGION_ID", "IMG_1", ImageClass.MACHINE, MachineImageState.ACTIVE, "IMAGE_1", "IMAGE1_DESCRIPTION", Architecture.I64, Platform.CENT_OS);
        img = img.sharedWithPublic();
        img.setTag("public", "true");
        list.add(img);
        img = MachineImage.getInstance("OWNER", "REGION_ID", "IMG_2", ImageClass.MACHINE, MachineImageState.ACTIVE, "IMAGE_2", "IMAGE2_DESCRIPTION", Architecture.I64, Platform.CENT_OS);
        list.add(img);
        img = MachineImage.getInstance("OWNER", "REGION_ID", "IMG_3", ImageClass.MACHINE, MachineImageState.ACTIVE, "IMAGE_3", "IMAGE3_DESCRIPTION", Architecture.I64, Platform.CENT_OS);
        list.add(img);
        img = MachineImage.getInstance("OWNER", "REGION_ID", "IMG_4", ImageClass.MACHINE, MachineImageState.ACTIVE, "IMAGE_4", "IMAGE4_DESCRIPTION", Architecture.I64, Platform.CENT_OS);
        list.add(img);
        img = MachineImage.getInstance("OWNER", "REGION_ID", "IMG_5", ImageClass.MACHINE, MachineImageState.ACTIVE, "IMAGE_5", "IMAGE5_DESCRIPTION", Architecture.I64, Platform.CENT_OS);
        list.add(img);
        return list;
    }

    @Before
    public void setUp() {
        templateSupport = new TemplateSupport(vcloudMock);
    }

    @Test
    public void listPublicTemplates() throws CloudException, InternalException {
        new NonStrictExpectations(TemplateSupport.class){
            {
                templateSupport.searchPublicImages((ImageFilterOptions) any);
                    result = getTestTemplateList();
            }
        };

        Iterable<MachineImage> images = templateSupport.searchPublicImages(ImageFilterOptions.getInstance());
        for (MachineImage image : images) {
            Boolean isPublicTag = (image.getTag("public") != null);
            Boolean isPublic = image.isPublic();
            assertEquals("The public tag and isPublic attributes do not match", isPublic, isPublicTag);
        }
    }

    @Test
    public void listTemplatesFilterShouldBeAppliedToCache() throws CloudException, InternalException {
        imgCache = Cache.getInstance(vcloudMock, "listImages", MachineImage.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Minute>(6, TimePeriod.MINUTE));
        imgCache.put(providerContextMock, getTestTemplateList());

        new NonStrictExpectations(TemplateSupport.class) {
            {
                vcloudMock.getContext();
                result = providerContextMock;
            }
        };

        imgCache.put(providerContextMock, getTestTemplateList());
        Iterable<MachineImage> images = templateSupport.listImages(ImageFilterOptions.getInstance().withImageClass(ImageClass.KERNEL));
        assertFalse("There are no kernel images in test list so returned iterable should be empty", images.iterator().hasNext());
    }
}
