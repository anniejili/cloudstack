// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.vm;

import com.cloud.configuration.Resource;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.event.UsageEventUtils;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDaoImpl;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcManager;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Grouping;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOSCategoryVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.UserData;
import com.cloud.user.UserDataVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.user.dao.UserDataDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.UUIDManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExceptionProxyObject;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.UserVmDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.BaseCmd.HTTPMethod;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMUserDataCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.service.api.OrchestrationService;
import org.apache.cloudstack.reservation.ReservationVO;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@PrepareForTest({GlobalLock.class, CallContext.class, UsageEventUtils.class})
@RunWith(PowerMockRunner.class)
public class UserVmManagerImplTest {

    @Spy
    @InjectMocks
    private UserVmManagerImpl userVmManagerImpl = new UserVmManagerImpl();

    @Mock
    private ServiceOfferingDao _serviceOfferingDao;

    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    private DataCenterDao _dcDao;
    @Mock
    private DataCenterVO _dcMock;

    @Mock
    protected NicDao nicDao;

    @Mock
    private NetworkDao _networkDao;

    @Mock
    private NetworkOrchestrationService _networkMgr;

    @Mock
    private NetworkVO _networkMock;

    @Mock
    private GuestOSDao guestOSDao;

    @Mock
    private UserVmDao userVmDao;

    @Mock
    private UpdateVMCmd updateVmCommand;

    @Mock
    private AccountManager accountManager;

    @Mock
    private AccountService accountService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private UserVmDetailsDao userVmDetailsDao;

    @Mock
    private UserVmVO userVmVoMock;

    @Mock
    private NetworkModel networkModel;

    @Mock
    private Account accountMock;

    @Mock
    private AccountVO callerAccount;

    @Mock
    private UserVO callerUser;

    @Mock
    private VMTemplateDao templateDao;

    @Mock
    private AccountDao accountDao;

    @Mock
    ResourceLimitService resourceLimitMgr;

    @Mock
    VolumeApiService volumeApiService;

    @Mock
    UserDataDao userDataDao;

    @Mock
    private VolumeVO volumeVOMock;

    @Mock
    private VolumeDao volumeDaoMock;

    @Mock
    AccountVO account;

    @Mock
    private ServiceOfferingVO serviceOffering;

    @Mock
    private OrchestrationService orchestrationService;

    @Mock
    private VpcManager vpcMgr;

    @Mock
    private NetworkDaoImpl networkDao;

    @Mock
    private DedicatedResourceDao dedicatedDao;

    @Mock
    private GlobalLock quotaLimitLock;

    @Mock
    private ReservationDao reservationDao;

    @Mock
    private PrimaryDataStoreDao storagePoolDao;

    @Mock
    private VMTemplateZoneDao templateZoneDao;

    @Mock
    private UUIDManager uuidManager;

    @Mock
    private VMInstanceDao vmInstanceDao;

    @Mock
    private UserDao userDao;

    @Mock
    private GuestOSCategoryDao guestOSCategoryDao;

    @Mock
    private UsageEventDao usageEventDao;

    private static final long vmId = 1l;
    private static final long zoneId = 2L;
    private static final long accountId = 3L;
    private static final long serviceOfferingId = 10L;

    private static final long GiB_TO_BYTES = 1024 * 1024 * 1024;

    private Map<String, String> customParameters = new HashMap<>();

    private DiskOfferingVO smallerDisdkOffering = prepareDiskOffering(5l * GiB_TO_BYTES, 1l, 1L, 2L);
    private DiskOfferingVO largerDisdkOffering = prepareDiskOffering(10l * GiB_TO_BYTES, 2l, 10L, 20L);

    @Before
    public void beforeTest() {

        Mockito.when(updateVmCommand.getId()).thenReturn(vmId);

        when(_dcDao.findById(anyLong())).thenReturn(_dcMock);

        Mockito.when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);

        Mockito.when(callerAccount.getType()).thenReturn(Account.Type.ADMIN);
        CallContext.register(callerUser, callerAccount);

        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, "123");
        lenient().doNothing().when(resourceLimitMgr).incrementResourceCount(anyLong(), any(Resource.ResourceType.class));
        lenient().doNothing().when(resourceLimitMgr).decrementResourceCount(anyLong(), any(Resource.ResourceType.class), anyLong());
    }

    @After
    public void afterTest() {
        CallContext.unregister();
    }

    @Test
    public void validateGuestOsIdForUpdateVirtualMachineCommandTestOsTypeNull() {
        Mockito.when(updateVmCommand.getOsTypeId()).thenReturn(null);
        userVmManagerImpl.validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateGuestOsIdForUpdateVirtualMachineCommandTestOsTypeNotFound() {
        Mockito.when(updateVmCommand.getOsTypeId()).thenReturn(1l);

        userVmManagerImpl.validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
    }

    @Test
    public void validateGuestOsIdForUpdateVirtualMachineCommandTestOsTypeFound() {
        Mockito.when(updateVmCommand.getOsTypeId()).thenReturn(1l);
        Mockito.when(guestOSDao.findById(1l)).thenReturn(Mockito.mock(GuestOSVO.class));

        userVmManagerImpl.validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateInputsAndPermissionForUpdateVirtualMachineCommandTestVmNotFound() {
        Mockito.when(userVmDao.findById(vmId)).thenReturn(null);

        userVmManagerImpl.validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);
    }

    private ServiceOfferingVO getSvcoffering(int ramSize) {
        String name = "name";
        String displayText = "displayText";
        int cpu = 1;
        int speed = 128;

        boolean ha = false;
        boolean useLocalStorage = false;

        ServiceOfferingVO serviceOffering = new ServiceOfferingVO(name, cpu, ramSize, speed, null, null, ha, displayText, false, null,
                false);
        serviceOffering.setDiskOfferingId(1l);
        return serviceOffering;
    }

    @Test
    @PrepareForTest(CallContext.class)
    public void validateInputsAndPermissionForUpdateVirtualMachineCommandTest() {
        Mockito.doNothing().when(userVmManagerImpl).validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);

        CallContext callContextMock = Mockito.mock(CallContext.class);

        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        ServiceOffering offering = getSvcoffering(512);
        Mockito.lenient().when(_serviceOfferingDao.findById(Mockito.anyLong(), Mockito.anyLong())).thenReturn((ServiceOfferingVO) offering);
        Mockito.lenient().doNothing().when(accountManager).checkAccess(accountMock, null, true, userVmVoMock);
        userVmManagerImpl.validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);

        Mockito.verify(userVmManagerImpl).validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
        Mockito.verify(accountManager).checkAccess(callerAccount, null, true, userVmVoMock);
    }

    @Test
    public void updateVirtualMachineTestDisplayChanged() throws ResourceUnavailableException, InsufficientCapacityException {
        configureDoNothingForMethodsThatWeDoNotWantToTest();
        ServiceOffering offering = getSvcoffering(512);
        Mockito.when(_serviceOfferingDao.findById(Mockito.anyLong(), Mockito.anyLong())).thenReturn((ServiceOfferingVO) offering);
        Mockito.when(userVmVoMock.isDisplay()).thenReturn(true);
        Mockito.doNothing().when(userVmManagerImpl).updateDisplayVmFlag(false, vmId, userVmVoMock);
        Mockito.when(updateVmCommand.getUserdataId()).thenReturn(null);
        userVmManagerImpl.updateVirtualMachine(updateVmCommand);
        verifyMethodsThatAreAlwaysExecuted();

        Mockito.verify(userVmManagerImpl).updateDisplayVmFlag(false, vmId, userVmVoMock);
        Mockito.verify(userVmDetailsDao, Mockito.times(0)).removeDetail(anyLong(), anyString());
    }

    @Test
    public void updateVirtualMachineTestCleanUpTrue() throws ResourceUnavailableException, InsufficientCapacityException {
        configureDoNothingForMethodsThatWeDoNotWantToTest();
        ServiceOffering offering = getSvcoffering(512);
        Mockito.when(_serviceOfferingDao.findById(Mockito.anyLong(), Mockito.anyLong())).thenReturn((ServiceOfferingVO) offering);
        Mockito.when(updateVmCommand.isCleanupDetails()).thenReturn(true);
        Mockito.lenient().doNothing().when(userVmManagerImpl).updateDisplayVmFlag(false, vmId, userVmVoMock);

        Mockito.when(updateVmCommand.getUserdataId()).thenReturn(null);

        prepareExistingDetails(vmId, "userdetail");

        userVmManagerImpl.updateVirtualMachine(updateVmCommand);
        verifyMethodsThatAreAlwaysExecuted();
        Mockito.verify(userVmDetailsDao).removeDetail(vmId, "userdetail");
        Mockito.verify(userVmDetailsDao, Mockito.times(0)).removeDetail(vmId, "systemdetail");
        Mockito.verify(userVmManagerImpl, Mockito.times(0)).updateDisplayVmFlag(false, vmId, userVmVoMock);
    }

    @Test
    public void updateVirtualMachineTestCleanUpTrueAndDetailEmpty() throws ResourceUnavailableException, InsufficientCapacityException {
        prepareAndExecuteMethodDealingWithDetails(true, true);
    }

    @Test
    public void updateVirtualMachineTestCleanUpTrueAndDetailsNotEmpty() throws ResourceUnavailableException, InsufficientCapacityException {
        prepareAndExecuteMethodDealingWithDetails(true, false);
    }

    @Test
    public void updateVirtualMachineTestCleanUpFalseAndDetailsNotEmpty() throws ResourceUnavailableException, InsufficientCapacityException {
        prepareAndExecuteMethodDealingWithDetails(false, true);
    }

    @Test
    public void updateVirtualMachineTestCleanUpFalseAndDetailsEmpty() throws ResourceUnavailableException, InsufficientCapacityException {
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(callerAccount);
        prepareAndExecuteMethodDealingWithDetails(false, false);
    }

    private List<UserVmDetailVO> prepareExistingDetails(Long vmId, String... existingDetailKeys) {
        List<UserVmDetailVO> existingDetails = new ArrayList<>();
        for (String detail : existingDetailKeys) {
            existingDetails.add(new UserVmDetailVO(vmId, detail, "foo", true));
        }
        existingDetails.add(new UserVmDetailVO(vmId, "systemdetail", "bar", false));
        Mockito.when(userVmDetailsDao.listDetails(vmId)).thenReturn(existingDetails);
        return existingDetails;
    }

    private void prepareAndExecuteMethodDealingWithDetails(boolean cleanUpDetails, boolean isDetailsEmpty) throws ResourceUnavailableException, InsufficientCapacityException {
        configureDoNothingForMethodsThatWeDoNotWantToTest();

        ServiceOffering offering = getSvcoffering(512);
        Mockito.when(_serviceOfferingDao.findById(Mockito.anyLong(), Mockito.anyLong())).thenReturn((ServiceOfferingVO) offering);
        Mockito.when(_serviceOfferingDao.findByIdIncludingRemoved(Mockito.anyLong(), Mockito.anyLong())).thenReturn((ServiceOfferingVO) offering);
        ServiceOfferingVO currentServiceOffering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.lenient().when(currentServiceOffering.getCpu()).thenReturn(1);
        Mockito.lenient().when(currentServiceOffering.getRamSize()).thenReturn(512);

        List<NicVO> nics = new ArrayList<>();
        NicVO nic1 = mock(NicVO.class);
        NicVO nic2 = mock(NicVO.class);
        nics.add(nic1);
        nics.add(nic2);
        when(this.nicDao.listByVmId(Mockito.anyLong())).thenReturn(nics);
        when(_networkDao.findById(anyLong())).thenReturn(_networkMock);
        lenient().doNothing().when(_networkMgr).saveExtraDhcpOptions(anyString(), anyLong(), anyMap());
        HashMap<String, String> details = new HashMap<>();
        if(!isDetailsEmpty) {
            details.put("newdetail", "foo");
        }
        prepareExistingDetails(vmId, "existingdetail");
        Mockito.when(updateVmCommand.getUserdataId()).thenReturn(null);
        Mockito.when(updateVmCommand.getDetails()).thenReturn(details);
        Mockito.when(updateVmCommand.isCleanupDetails()).thenReturn(cleanUpDetails);
        configureDoNothingForDetailsMethod();

        userVmManagerImpl.updateVirtualMachine(updateVmCommand);
        verifyMethodsThatAreAlwaysExecuted();

        Mockito.verify(userVmVoMock, Mockito.times(cleanUpDetails || isDetailsEmpty ? 0 : 1)).setDetails(details);
        Mockito.verify(userVmDetailsDao, Mockito.times(cleanUpDetails ? 1 : 0)).removeDetail(vmId, "existingdetail");
        Mockito.verify(userVmDetailsDao, Mockito.times(0)).removeDetail(vmId, "systemdetail");
        Mockito.verify(userVmDao, Mockito.times(cleanUpDetails || isDetailsEmpty ? 0 : 1)).saveDetails(userVmVoMock);
        Mockito.verify(userVmManagerImpl, Mockito.times(0)).updateDisplayVmFlag(false, vmId, userVmVoMock);
    }

    private void configureDoNothingForDetailsMethod() {
        Mockito.lenient().doNothing().when(userVmManagerImpl).updateDisplayVmFlag(false, vmId, userVmVoMock);
        Mockito.doNothing().when(userVmDetailsDao).removeDetail(anyLong(), anyString());
        Mockito.doNothing().when(userVmDao).saveDetails(userVmVoMock);
    }

    @SuppressWarnings("unchecked")
    private void verifyMethodsThatAreAlwaysExecuted() throws ResourceUnavailableException, InsufficientCapacityException {
        Mockito.verify(userVmManagerImpl).validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);
        Mockito.verify(userVmManagerImpl).getSecurityGroupIdList(updateVmCommand);

        Mockito.verify(userVmManagerImpl).updateVirtualMachine(nullable(Long.class), nullable(String.class), nullable(String.class), nullable(Boolean.class),
                nullable(Boolean.class), nullable(Long.class),
                nullable(String.class), nullable(Long.class), nullable(String.class), nullable(Boolean.class), nullable(HTTPMethod.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(List.class),
                nullable(Map.class));

    }

    @SuppressWarnings("unchecked")
    private void configureDoNothingForMethodsThatWeDoNotWantToTest() throws ResourceUnavailableException, InsufficientCapacityException {
        Mockito.doNothing().when(userVmManagerImpl).validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);
        Mockito.doReturn(new ArrayList<Long>()).when(userVmManagerImpl).getSecurityGroupIdList(updateVmCommand);
        Mockito.lenient().doReturn(Mockito.mock(UserVm.class)).when(userVmManagerImpl).updateVirtualMachine(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(),
                Mockito.anyBoolean(), Mockito.anyLong(),
                Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any(HTTPMethod.class), Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyListOf(Long.class),
                Mockito.anyMap());
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressValid() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(0, "01:23:45:67:89:ab", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNull() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, null, "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressBlank() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, " ", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressEmpty() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption1() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "abcdef:gh:ij:kl", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption2() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "01:23:45:67:89:", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption3() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "01:23:45:67:89:az", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption4() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "@1:23:45:67:89:ab", "01:23:45:67:89:ab");
    }

    private void configureValidateOrReplaceMacAddressTest(int times, String macAddress, String expectedMacAddress) throws InsufficientAddressCapacityException {
        Mockito.when(networkModel.getNextAvailableMacAddressInNetwork(Mockito.anyLong())).thenReturn(expectedMacAddress);

        String returnedMacAddress = userVmManagerImpl.validateOrReplaceMacAddress(macAddress, 1l);

        Mockito.verify(networkModel, Mockito.times(times)).getNextAvailableMacAddressInNetwork(Mockito.anyLong());
        assertEquals(expectedMacAddress, returnedMacAddress);
    }

    @Test
    public void testValidatekeyValuePair() throws Exception {
        assertTrue(userVmManagerImpl.isValidKeyValuePair("is-a-template=true\nHVM-boot-policy=\nPV-bootloader=pygrub\nPV-args=hvc0"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("is-a-template=true HVM-boot-policy= PV-bootloader=pygrub PV-args=hvc0"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("nvp.vm-uuid=34b3d5ea-1c25-4bb0-9250-8dc3388bfa9b"));
        assertFalse(userVmManagerImpl.isValidKeyValuePair("key"));
        //key-1=value1, param:key-2=value2, my.config.v0=False"
        assertTrue(userVmManagerImpl.isValidKeyValuePair("key-1=value1"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("param:key-2=value2"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("my.config.v0=False"));
    }

    @Test
    public void configureCustomRootDiskSizeTest() {
        String vmDetailsRootDiskSize = "123";
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, vmDetailsRootDiskSize);
        long expectedRootDiskSize = 123l * GiB_TO_BYTES;
        long offeringRootDiskSize = 0l;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void configureCustomRootDiskSizeTestExpectExceptionZero() {
        String vmDetailsRootDiskSize = "0";
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, vmDetailsRootDiskSize);
        long expectedRootDiskSize = 0l;
        long offeringRootDiskSize = 0l;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void configureCustomRootDiskSizeTestExpectExceptionNegativeNum() {
        String vmDetailsRootDiskSize = "-123";
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, vmDetailsRootDiskSize);
        long expectedRootDiskSize = -123l * GiB_TO_BYTES;
        long offeringRootDiskSize = 0l;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test
    public void configureCustomRootDiskSizeTestEmptyParameters() {
        Map<String, String> customParameters = new HashMap<>();
        long expectedRootDiskSize = 99l * GiB_TO_BYTES;
        long offeringRootDiskSize = 0l;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test
    public void configureCustomRootDiskSizeTestEmptyParametersAndOfferingRootSize() {
        Map<String, String> customParameters = new HashMap<>();
        long expectedRootDiskSize = 10l * GiB_TO_BYTES;
        long offeringRootDiskSize = 10l * GiB_TO_BYTES;;

        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    private void prepareAndRunConfigureCustomRootDiskSizeTest(Map<String, String> customParameters, long expectedRootDiskSize, int timesVerifyIfHypervisorSupports, Long offeringRootDiskSize) {
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getId()).thenReturn(1l);
        Mockito.when(template.getSize()).thenReturn(99L * GiB_TO_BYTES);
        Mockito.when(templateDao.findById(Mockito.anyLong())).thenReturn(template);

        DiskOfferingVO diskfferingVo = Mockito.mock(DiskOfferingVO.class);

        Mockito.when(diskfferingVo.getDiskSize()).thenReturn(offeringRootDiskSize);

        Mockito.when(volumeApiService.validateVolumeSizeInBytes(Mockito.anyLong())).thenReturn(true);
        long rootDiskSize = userVmManagerImpl.configureCustomRootDiskSize(customParameters, template, Hypervisor.HypervisorType.KVM, diskfferingVo);

        Assert.assertEquals(expectedRootDiskSize, rootDiskSize);
        Mockito.verify(userVmManagerImpl, Mockito.times(timesVerifyIfHypervisorSupports)).verifyIfHypervisorSupportsRootdiskSizeOverride(Mockito.any());
    }

    @Test
    public void verifyIfHypervisorSupportRootdiskSizeOverrideTest() {
        Hypervisor.HypervisorType[] hypervisorTypeArray = Hypervisor.HypervisorType.values();
        int exceptionCounter = 0;
        int expectedExceptionCounter = hypervisorTypeArray.length - 4;

        for(int i = 0; i < hypervisorTypeArray.length; i++) {
            if (Hypervisor.HypervisorType.KVM == hypervisorTypeArray[i]
                    || Hypervisor.HypervisorType.XenServer == hypervisorTypeArray[i]
                    || Hypervisor.HypervisorType.VMware == hypervisorTypeArray[i]
                    || Hypervisor.HypervisorType.Simulator == hypervisorTypeArray[i]) {
                userVmManagerImpl.verifyIfHypervisorSupportsRootdiskSizeOverride(hypervisorTypeArray[i]);
            } else {
                try {
                    userVmManagerImpl.verifyIfHypervisorSupportsRootdiskSizeOverride(hypervisorTypeArray[i]);
                } catch (InvalidParameterValueException e) {
                    exceptionCounter ++;
                }
            }
        }

        Assert.assertEquals(expectedExceptionCounter, exceptionCounter);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void prepareResizeVolumeCmdTestRootVolumeNull() {
        DiskOfferingVO newRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        DiskOfferingVO currentRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        userVmManagerImpl.prepareResizeVolumeCmd(null, currentRootDiskOffering, newRootDiskOffering);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void prepareResizeVolumeCmdTestCurrentRootDiskOffering() {
        DiskOfferingVO newRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        VolumeVO rootVolumeOfVm = Mockito.mock(VolumeVO.class);
        userVmManagerImpl.prepareResizeVolumeCmd(rootVolumeOfVm, null, newRootDiskOffering);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void prepareResizeVolumeCmdTestNewRootDiskOffering() {
        VolumeVO rootVolumeOfVm = Mockito.mock(VolumeVO.class);
        DiskOfferingVO currentRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        userVmManagerImpl.prepareResizeVolumeCmd(rootVolumeOfVm, currentRootDiskOffering, null);
    }

    @Test
    public void prepareResizeVolumeCmdTestNewOfferingLarger() {
        prepareAndRunResizeVolumeTest(2L, 10L, 20L, smallerDisdkOffering, largerDisdkOffering);
    }

    @Test
    public void prepareResizeVolumeCmdTestSameOfferingSize() {
        prepareAndRunResizeVolumeTest(null, 1L, 2L, smallerDisdkOffering, smallerDisdkOffering);
    }

    @Test
    public void prepareResizeVolumeCmdTestOfferingRootSizeZero() {
        DiskOfferingVO rootSizeZero = prepareDiskOffering(0l, 3l, 100L, 200L);
        prepareAndRunResizeVolumeTest(null, 100L, 200L, smallerDisdkOffering, rootSizeZero);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void prepareResizeVolumeCmdTestNewOfferingSmaller() {
        prepareAndRunResizeVolumeTest(2L, 10L, 20L, largerDisdkOffering, smallerDisdkOffering);
    }

    @Test
    public void validateDiskOfferingCheckForEncryption1Test() {
        ServiceOfferingVO currentOffering = prepareOfferingsForEncryptionValidation(1L, true);
        ServiceOfferingVO newOffering = prepareOfferingsForEncryptionValidation(2L, true);
        userVmManagerImpl.validateDiskOfferingChecks(currentOffering, newOffering);
    }

    @Test
    public void validateDiskOfferingCheckForEncryption2Test() {
        ServiceOfferingVO currentOffering = prepareOfferingsForEncryptionValidation(1L, false);
        ServiceOfferingVO newOffering = prepareOfferingsForEncryptionValidation(2L, false);
        userVmManagerImpl.validateDiskOfferingChecks(currentOffering, newOffering);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void validateDiskOfferingCheckForEncryptionFail1Test() {
        ServiceOfferingVO currentOffering = prepareOfferingsForEncryptionValidation(1L, false);
        ServiceOfferingVO newOffering = prepareOfferingsForEncryptionValidation(2L, true);
        userVmManagerImpl.validateDiskOfferingChecks(currentOffering, newOffering);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void validateDiskOfferingCheckForEncryptionFail2Test() {
        ServiceOfferingVO currentOffering = prepareOfferingsForEncryptionValidation(1L, true);
        ServiceOfferingVO newOffering = prepareOfferingsForEncryptionValidation(2L, false);
        userVmManagerImpl.validateDiskOfferingChecks(currentOffering, newOffering);
    }

    private void prepareAndRunResizeVolumeTest(Long expectedOfferingId, long expectedMinIops, long expectedMaxIops, DiskOfferingVO currentRootDiskOffering, DiskOfferingVO newRootDiskOffering) {
        long rootVolumeId = 1l;
        VolumeVO rootVolumeOfVm = Mockito.mock(VolumeVO.class);
        Mockito.when(rootVolumeOfVm.getId()).thenReturn(rootVolumeId);

        ResizeVolumeCmd resizeVolumeCmd = userVmManagerImpl.prepareResizeVolumeCmd(rootVolumeOfVm, currentRootDiskOffering, newRootDiskOffering);

        Assert.assertEquals(rootVolumeId, resizeVolumeCmd.getId().longValue());
        Assert.assertEquals(expectedOfferingId, resizeVolumeCmd.getNewDiskOfferingId());
        Assert.assertEquals(expectedMinIops, resizeVolumeCmd.getMinIops().longValue());
        Assert.assertEquals(expectedMaxIops, resizeVolumeCmd.getMaxIops().longValue());
    }

    private DiskOfferingVO prepareDiskOffering(long rootSize, long diskOfferingId, long offeringMinIops, long offeringMaxIops) {
        DiskOfferingVO newRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(newRootDiskOffering.getDiskSize()).thenReturn(rootSize);
        Mockito.when(newRootDiskOffering.getId()).thenReturn(diskOfferingId);
        Mockito.when(newRootDiskOffering.getMinIops()).thenReturn(offeringMinIops);
        Mockito.when(newRootDiskOffering.getMaxIops()).thenReturn(offeringMaxIops);
        Mockito.when(newRootDiskOffering.getName()).thenReturn("OfferingName");
        return newRootDiskOffering;
    }

    private ServiceOfferingVO prepareOfferingsForEncryptionValidation(long diskOfferingId, boolean encryption) {
        ServiceOfferingVO svcOffering = Mockito.mock(ServiceOfferingVO.class);
        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);

        Mockito.when(svcOffering.getDiskOfferingId()).thenReturn(diskOfferingId);
        Mockito.when(diskOffering.getEncrypt()).thenReturn(encryption);

        // Be aware - Multiple calls with the same disk offering ID could conflict
        Mockito.when(diskOfferingDao.findByIdIncludingRemoved(diskOfferingId)).thenReturn(diskOffering);
        Mockito.when(diskOfferingDao.findById(diskOfferingId)).thenReturn(diskOffering);

        return svcOffering;
    }

    @Test (expected = CloudRuntimeException.class)
    public void testUserDataDenyOverride() {
        Long userDataId = 1L;

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(2L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.DENYOVERRIDE);

        userVmManagerImpl.finalizeUserData(null, userDataId, template);
    }

    @Test
    public void testUserDataAllowOverride() {
        String templateUserData = "testTemplateUserdata";
        Long userDataId = 1L;

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(2L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.ALLOWOVERRIDE);

        UserDataVO apiUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(apiUserDataVO).when(userDataDao).findById(userDataId);
        when(apiUserDataVO.getUserData()).thenReturn(templateUserData);

        String finalUserdata = userVmManagerImpl.finalizeUserData(null, userDataId, template);

        Assert.assertEquals(finalUserdata, templateUserData);
    }

    @Test
    public void testUserDataAppend() {
        String userData = "testUserdata";
        String templateUserData = "testTemplateUserdata";
        Long userDataId = 1L;

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(2L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.APPEND);

        UserDataVO templateUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(templateUserDataVO).when(userDataDao).findById(2L);
        when(templateUserDataVO.getUserData()).thenReturn(templateUserData);

        UserDataVO apiUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(apiUserDataVO).when(userDataDao).findById(userDataId);
        when(apiUserDataVO.getUserData()).thenReturn(userData);

        String finalUserdata = userVmManagerImpl.finalizeUserData(null, userDataId, template);

        Assert.assertEquals(finalUserdata, templateUserData+userData);
    }

    @Test
    public void testUserDataWithoutTemplate() {
        String userData = "testUserdata";
        Long userDataId = 1L;

        UserDataVO apiUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(apiUserDataVO).when(userDataDao).findById(userDataId);
        when(apiUserDataVO.getUserData()).thenReturn(userData);

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(null);

        String finalUserdata = userVmManagerImpl.finalizeUserData(null, userDataId, template);

        Assert.assertEquals(finalUserdata, userData);
    }

    @Test
    public void testUserDataAllowOverrideWithoutAPIuserdata() {
        String templateUserData = "testTemplateUserdata";

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(2L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.ALLOWOVERRIDE);
        UserDataVO templateUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(templateUserDataVO).when(userDataDao).findById(2L);
        when(templateUserDataVO.getUserData()).thenReturn(templateUserData);

        String finalUserdata = userVmManagerImpl.finalizeUserData(null, null, template);

        Assert.assertEquals(finalUserdata, templateUserData);
    }

    @Test
    public void testUserDataAllowOverrideWithUserdataText() {
        String userData = "testUserdata";
        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(null);

        String finalUserdata = userVmManagerImpl.finalizeUserData(userData, null, template);

        Assert.assertEquals(finalUserdata, userData);
    }

    @Test(expected = InvalidParameterValueException.class)
    @PrepareForTest(CallContext.class)
    public void testResetVMUserDataVMStateNotStopped() {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(userVmDao.findById(1L)).thenReturn(userVmVoMock);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        when(userVmVoMock.getTemplateId()).thenReturn(2L);
        when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);


        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Running);

        try {
            userVmManagerImpl.resetVMUserData(cmd);
        } catch (ResourceUnavailableException e) {
            throw new RuntimeException(e);
        } catch (InsufficientCapacityException e) {
            throw new RuntimeException(e);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    @PrepareForTest(CallContext.class)
    public void testResetVMUserDataDontAcceptBothUserdataAndUserdataId() {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(userVmDao.findById(1L)).thenReturn(userVmVoMock);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        when(userVmVoMock.getTemplateId()).thenReturn(2L);
        when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);


        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Stopped);

        when(cmd.getUserData()).thenReturn("testUserdata");
        when(cmd.getUserdataId()).thenReturn(1L);

        try {
            userVmManagerImpl.resetVMUserData(cmd);
        } catch (ResourceUnavailableException e) {
            throw new RuntimeException(e);
        } catch (InsufficientCapacityException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @PrepareForTest(CallContext.class)
    public void testResetVMUserDataSuccessResetWithUserdata() {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        UserVmVO userVmVO = new UserVmVO();
        userVmVO.setTemplateId(2L);
        userVmVO.setState(VirtualMachine.State.Stopped);
        userVmVO.setUserDataId(100L);
        userVmVO.setUserData("RandomUserdata");

        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(userVmDao.findById(1L)).thenReturn(userVmVO);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);
        when(template.getUserDataId()).thenReturn(null);

        when(cmd.getUserData()).thenReturn("testUserdata");
        when(cmd.getUserdataId()).thenReturn(null);
        when(cmd.getHttpMethod()).thenReturn(HTTPMethod.GET);

        try {
            doNothing().when(userVmManagerImpl).updateUserData(userVmVO);
            userVmManagerImpl.resetVMUserData(cmd);
        } catch (ResourceUnavailableException e) {
            throw new RuntimeException(e);
        } catch (InsufficientCapacityException e) {
            throw new RuntimeException(e);
        }

        Assert.assertEquals("testUserdata", userVmVO.getUserData());
        Assert.assertEquals(null, userVmVO.getUserDataId());
    }

    @Test
    @PrepareForTest(CallContext.class)
    public void testResetVMUserDataSuccessResetWithUserdataId() {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        UserVmVO userVmVO = new UserVmVO();
        userVmVO.setTemplateId(2L);
        userVmVO.setState(VirtualMachine.State.Stopped);
        userVmVO.setUserDataId(100L);
        userVmVO.setUserData("RandomUserdata");

        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(userVmDao.findById(1L)).thenReturn(userVmVO);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);
        when(template.getUserDataId()).thenReturn(null);

        when(cmd.getUserdataId()).thenReturn(1L);
        UserDataVO apiUserDataVO = Mockito.mock(UserDataVO.class);
        when(userDataDao.findById(1L)).thenReturn(apiUserDataVO);
        when(apiUserDataVO.getUserData()).thenReturn("testUserdata");
        when(cmd.getHttpMethod()).thenReturn(HTTPMethod.GET);

        try {
            doNothing().when(userVmManagerImpl).updateUserData(userVmVO);
            userVmManagerImpl.resetVMUserData(cmd);
        } catch (ResourceUnavailableException e) {
            throw new RuntimeException(e);
        } catch (InsufficientCapacityException e) {
            throw new RuntimeException(e);
        }

        Assert.assertEquals("testUserdata", userVmVO.getUserData());
        Assert.assertEquals(1L, (long)userVmVO.getUserDataId());
    }

    @Test
    public void recoverRootVolumeTestDestroyState() {
        Mockito.doReturn(Volume.State.Destroy).when(volumeVOMock).getState();

        userVmManagerImpl.recoverRootVolume(volumeVOMock, vmId);

        Mockito.verify(volumeApiService).recoverVolume(volumeVOMock.getId());
        Mockito.verify(volumeDaoMock).attachVolume(volumeVOMock.getId(), vmId, UserVmManagerImpl.ROOT_DEVICE_ID);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void createVirtualMachineWithInactiveServiceOffering() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = new DeployVMCmd();
        ReflectionTestUtils.setField(deployVMCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(deployVMCmd, "serviceOfferingId", serviceOfferingId);
        deployVMCmd._accountService = accountService;

        when(accountService.finalyzeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(serviceOffering.getState()).thenReturn(ServiceOffering.State.Inactive);

        userVmManagerImpl.createVirtualMachine(deployVMCmd);
    }

    @Test
    public void vmCreateExceptionVmIdPropagation() throws InsufficientCapacityException, ResourceAllocationException{
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        long id = 7L;
        long zoneId = 2L;
        long accountId = 5L;
        Long diskOfferingId = 4L;
        Long diskSize = 1024L;
        long userId = 6L;
        Long networkId = 15L;
        Long volumeSize = 2048L;
        long guestOsId = 16L;
        long templateId = 17L;
        long reservationId = 17l;
        long guestOSCategoryId = 18l;
        Integer cpuSize = 8;
        Integer ramSize = 1024;
        Integer cpuSpeed = 100;

        String uuid = "uuid";
        String hostName = "test";
        String displayName = "testDisplayName";
        String instanceName = "testInstanceName";
        String uuidName = "testUuidName";
        String vmType = "testVmType";
        String base64UserData = "testUserData";
        vm.setUuid(uuid);
        DataCenter zone = Mockito.mock(DataCenter.class);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Account owner = Mockito.mock(Account.class);
        when(owner.getAccountId()).thenReturn(accountId);
        String userData = null;
        Long userDataId = null;
        String userDataDetails = null;
        Boolean isDisplayVm = false;
        String keyboard = null;

        Long rootDiskOfferingId = diskOfferingId;
        String sshkeypairs = null;
        Long overrideDiskOfferingId = diskOfferingId;
        ServiceOffering offering = Mockito.mock(ServiceOffering.class);
        boolean isIso = false;
        String sshPublicKeys = null;
        resourceLimitMgr = Mockito.mock(ResourceLimitService.class);
        when(template.getId()).thenReturn(templateId);
        LinkedHashMap<String, List<NicProfile>> networkNicMap = new LinkedHashMap<String, List<NicProfile>>();

        Hypervisor.HypervisorType hypervisorType = Hypervisor.HypervisorType.KVM;
        Map<String, String> customParameters = null;
        Map<String, Map<Integer, String>> extraDhcpOptionMap = null;
        Map<Long, DiskOffering> dataDiskTemplateToDiskOfferingMap = null;
        Map<String, String> userVmOVFPropertiesMap = null;
        VirtualMachine.PowerState powerState = VirtualMachine.PowerState.PowerOn;
        boolean dynamicScalingEnabled = false;
        List<Long> networkIdList = Arrays.asList(networkId);
        List<String> keypairs = new ArrayList<String>();
        Network.IpAddresses addrs = new Network.IpAddresses(null, null);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        NetworkOffering networkOffering = Mockito.mock(NetworkOffering.class);
        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        when(diskOffering.getDiskSize()).thenReturn(volumeSize);
        customParameters = Mockito.mock(HashMap.class);
        ReservationVO reservationVO = Mockito.mock(ReservationVO.class);
        when(customParameters.containsKey(VmDetailConstants.ROOT_DISK_SIZE)).thenReturn(Boolean.FALSE);

        when(vpcMgr.getSupportedVpcHypervisors()).thenReturn(Arrays.asList(Hypervisor.HypervisorType.KVM));
        when(_networkDao.findById(networkId)).thenReturn(network);
        when(network.getVpcId()).thenReturn(null);
        when(entityManager.findById(NetworkOffering.class, network.getNetworkOfferingId())).thenReturn(networkOffering);
        when(networkOffering.isSystemOnly()).thenReturn(Boolean.FALSE);
        when(owner.getState()).thenReturn(Account.State.ENABLED);
        when(templateDao.findById(template.getId())).thenReturn((VMTemplateVO) template);
        when(template.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(owner.getId()).thenReturn(accountId);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        when(accountManager.isRootAdmin(accountId)).thenReturn(Boolean.FALSE);
        when(dedicatedDao.findByZoneId(zone.getId())).thenReturn(null);
        when(_serviceOfferingDao.findById(serviceOffering.getId())).thenReturn(serviceOffering);
        when(serviceOffering.isDynamic()).thenReturn(Boolean.FALSE);
        when(serviceOffering.getCpu()).thenReturn(cpuSize);
        when(serviceOffering.getRamSize()).thenReturn(ramSize);
        when(serviceOffering.getSpeed()).thenReturn(cpuSpeed);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(serviceOffering.getDiskOfferingId()).thenReturn(diskOfferingId);
        when(diskOfferingDao.findById(diskOfferingId)).thenReturn(diskOffering);
        Mockito.doNothing().when(userVmManagerImpl).verifyIfHypervisorSupportsRootdiskSizeOverride(any());
        when(userVmManagerImpl.configureCustomRootDiskSize(customParameters, template, Hypervisor.HypervisorType.KVM, diskOffering)).thenReturn(volumeSize);
        when(diskOffering.getEncrypt()).thenReturn(Boolean.FALSE);
        when(diskOffering.isCustomized()).thenReturn(Boolean.FALSE);
        when(diskOffering.getDiskSize()).thenReturn(diskSize);
        CheckedReservation checkedReservation = Mockito.mock(CheckedReservation.class);
        PowerMockito.mockStatic(GlobalLock.class);
        GlobalLock lock = PowerMockito.mock(GlobalLock.class);
        PowerMockito.when(GlobalLock.getInternLock(anyString())).thenReturn(lock);
        when(storagePoolDao.countPoolsByStatus(StoragePoolStatus.Up)).thenReturn(2l);
        when(template.getTemplateType()).thenReturn(Storage.TemplateType.USER);
        VMTemplateZoneVO templateZoneVO = Mockito.mock(VMTemplateZoneVO.class);
        List<VMTemplateZoneVO> listZoneTemplate = Arrays.asList(templateZoneVO);
        when(templateZoneDao.listByZoneTemplate(zone.getId(), template.getId())).thenReturn(listZoneTemplate);
        when(userVmDao.getNextInSequence(any(), anyString())).thenReturn(id);
        when(uuidManager.generateUuid(UserVm.class, null)).thenReturn(uuid);
        when(vmInstanceDao.findVMByInstanceName(anyString())).thenReturn(null);
        PowerMockito.mockStatic(CallContext.class);
        CallContext callContext = Mockito.mock(CallContext.class);
        PowerMockito.when(CallContext.current()).thenReturn(callContext);
        when(callContext.getCallingAccount()).thenReturn(owner);
        when(template.getGuestOSId()).thenReturn(guestOsId);
        GuestOSVO guestOSVO = Mockito.mock(GuestOSVO.class);
        when(guestOSDao.findById(guestOsId)).thenReturn(guestOSVO);
        when(guestOSVO.getCategoryId()).thenReturn(guestOSCategoryId);
        GuestOSCategoryVO guestOSCategoryVO = Mockito.mock(GuestOSCategoryVO.class);
        when(guestOSCategoryDao.findById(guestOSCategoryId)).thenReturn(guestOSCategoryVO);
        try {
            PowerMockito.whenNew(CheckedReservation.class).withAnyArguments().thenReturn(checkedReservation);
        }catch (Exception e) {

        }
        Mockito.doReturn(Boolean.TRUE).when(quotaLimitLock).lock(120);
        Mockito.doReturn(Boolean.TRUE).when(lock).lock(120);
        //when(quotaLimitLock.lock(120)).thenReturn(Boolean.TRUE);
        Mockito.doNothing().when(resourceLimitMgr).checkResourceLimit(any(), any(), any());
        when(reservationDao.persist(any())).thenReturn(reservationVO);
        when(reservationVO.getId()).thenReturn(reservationId);
        when(serviceOffering.isDynamic()).thenReturn(Boolean.FALSE);
        PowerMockito.mockStatic(UsageEventUtils.class);
        UsageEventUtils usageEventUtils = Mockito.mock(UsageEventUtils.class);

        CloudRuntimeException cre = new CloudRuntimeException("Error and CloudRuntimeException is thrown");
        Mockito.doThrow(new CloudRuntimeException("Error and CloudRuntimeException is thrown")).when(orchestrationService).createVirtualMachine(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), nullable(Long.class), any(), any(), any(), any(), nullable(Long.class), nullable(Map.class),
                nullable(Map.class), nullable(Long.class), nullable(Long.class));

        Mockito.doThrow(new CloudRuntimeException("Error and CloudRuntimeException is thrown")).doNothing().when(userVmManagerImpl).resourceCountIncrement(5, null, 8L, 1024L);
        Mockito.doThrow(new CloudRuntimeException("Error and CloudRuntimeException is thrown")).doNothing().when(resourceLimitMgr).incrementResourceCount(anyLong(), any(Resource.ResourceType.class), any());
        willThrow(new CloudRuntimeException("Error and CloudRuntimeException is thrown")).given(resourceLimitMgr).incrementResourceCount(anyLong(), any(Resource.ResourceType.class), any(), any());

        try {
            UserVm vmCreated = userVmManagerImpl.createAdvancedVirtualMachine(zone, serviceOffering, template, networkIdList, owner,
                    hostName, hostName, null, null, null,
                    Hypervisor.HypervisorType.KVM, BaseCmd.HTTPMethod.POST, base64UserData, null, null, keypairs,
                    null, addrs, null, null, null, customParameters, null, null, null, null, true, UserVmManager.CKS_NODE, null);
        }catch (CloudRuntimeException crException) {
            ArrayList<ExceptionProxyObject> proxyIdList = crException.getIdProxyList();
            assertNotNull(proxyIdList != null );
            assertTrue(proxyIdList.stream().anyMatch( p -> p.getUuid().equals(uuid)));

        }
        catch (Exception e) {
            fail("No Exception is expected");
        }

    }
}
