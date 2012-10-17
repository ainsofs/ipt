/***************************************************************************
 * Copyright 2011 Global Biodiversity Information Facility Secretariat
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ***************************************************************************/

package org.gbif.ipt.service.manage.impl;

import org.gbif.ipt.action.BaseAction;
import org.gbif.ipt.config.AppConfig;
import org.gbif.ipt.config.Constants;
import org.gbif.ipt.config.DataDir;
import org.gbif.ipt.config.IPTModule;
import org.gbif.ipt.mock.MockAppConfig;
import org.gbif.ipt.mock.MockDataDir;
import org.gbif.ipt.mock.MockRegistryManager;
import org.gbif.ipt.model.Extension;
import org.gbif.ipt.model.ExtensionMapping;
import org.gbif.ipt.model.Ipt;
import org.gbif.ipt.model.Organisation;
import org.gbif.ipt.model.Resource;
import org.gbif.ipt.model.Source;
import org.gbif.ipt.model.User;
import org.gbif.ipt.model.User.Role;
import org.gbif.ipt.model.converter.ConceptTermConverter;
import org.gbif.ipt.model.converter.ExtensionRowTypeConverter;
import org.gbif.ipt.model.converter.JdbcInfoConverter;
import org.gbif.ipt.model.converter.OrganisationKeyConverter;
import org.gbif.ipt.model.converter.PasswordConverter;
import org.gbif.ipt.model.converter.UserEmailConverter;
import org.gbif.ipt.model.factory.ExtensionFactory;
import org.gbif.ipt.model.factory.ThesaurusHandlingRule;
import org.gbif.ipt.model.voc.PublicationStatus;
import org.gbif.ipt.service.AlreadyExistingException;
import org.gbif.ipt.service.ImportException;
import org.gbif.ipt.service.InvalidConfigException;
import org.gbif.ipt.service.admin.ExtensionManager;
import org.gbif.ipt.service.admin.RegistrationManager;
import org.gbif.ipt.service.admin.UserAccountManager;
import org.gbif.ipt.service.admin.VocabulariesManager;
import org.gbif.ipt.service.admin.impl.VocabulariesManagerImpl;
import org.gbif.ipt.service.manage.ResourceManager;
import org.gbif.ipt.service.manage.SourceManager;
import org.gbif.ipt.service.registry.RegistryManager;
import org.gbif.ipt.struts2.SimpleTextProvider;
import org.gbif.ipt.task.Eml2Rtf;
import org.gbif.ipt.task.GenerateDwcaFactory;
import org.gbif.metadata.eml.Eml;
import org.gbif.utils.file.CompressionUtil;
import org.gbif.utils.file.FileUtils;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.common.io.Files;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.ServletModule;
import com.google.inject.struts2.Struts2GuicePluginModule;
import org.apache.http.impl.client.DefaultHttpClient;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ResourceManagerImplTest {

  // Mock classes
  private AppConfig mockAppConfig = MockAppConfig.buildMock();
  private UserAccountManager mockUserAccountManager = mock(UserAccountManager.class);
  private UserEmailConverter mockEmailConverter = new UserEmailConverter(mockUserAccountManager);
  private RegistrationManager mockRegistrationManager = mock(RegistrationManager.class);
  private OrganisationKeyConverter mockOrganisationKeyConverter = new OrganisationKeyConverter(mockRegistrationManager);
  private JdbcInfoConverter mockJdbcConverter = mock(JdbcInfoConverter.class);
  private SourceManager mockSourceManager = mock(SourceManager.class);
  private RegistryManager mockRegistryManager = MockRegistryManager.buildMock();
  private GenerateDwcaFactory mockDwcaFactory = mock(GenerateDwcaFactory.class);
  private PasswordConverter mockPasswordConverter = mock(PasswordConverter.class);
  private Eml2Rtf mockEml2Rtf = mock(Eml2Rtf.class);
  private VocabulariesManager mockVocabulariesManager = mock(VocabulariesManager.class);
  private SimpleTextProvider mockSimpleTextProvider = mock(SimpleTextProvider.class);

  private DataDir mockedDataDir = MockDataDir.buildMock();
  private BaseAction baseAction = new BaseAction(mockSimpleTextProvider, mockAppConfig, mockRegistrationManager);

  private User creator;
  private Resource resource;
  private Ipt ipt;
  private Organisation organisation;

  @Before
  public void setup() {
    // create user.
    creator = new User();
    creator.setFirstname("Leonardo");
    creator.setLastname("Pisano");
    creator.setEmail("fi@liberabaci.com");
    creator.setLastLoginToNow();
    creator.setRole(Role.Manager);
    creator.setPassword("011235813");

    resource = new Resource();
    resource.setShortname("res2");

    organisation = new Organisation();
    organisation.setKey("f9b67ad0-9c9b-11d9-b9db-b8a03c50a862");
    organisation.setName("Academy of Natural Sciences");

    ipt = new Ipt();
    ipt.setKey("27c24cba-13c5-47d1-96a1-16abd8f11437");
    ipt.setName("Test IPT");

    when(mockedDataDir.tmpDir()).thenReturn(Files.createTempDir());
  }

  private ResourceManagerImpl getResourceManagerImpl() throws IOException, SAXException, ParserConfigurationException {

    // mock creation of datasetSubtypes Map, with 2 occurrence subtypes, and 6 checklist subtypes
    Map<String, String> datasetSubtypes = new LinkedHashMap<String, String>();
    datasetSubtypes.put("", "Select a subtype");
    datasetSubtypes.put("taxonomicAuthority", "Taxonomic Authority");
    datasetSubtypes.put("nomenclatorAuthority", "Nomenclator Authority");
    datasetSubtypes.put("inventoryThematic", "Inventory Thematic");
    datasetSubtypes.put("inventoryRegional", "Inventory Regional");
    datasetSubtypes.put("globalSpeciesDataset", "Global Species Dataset");
    datasetSubtypes.put("derivedFromOccurrence", "Derived from Occurrence");
    datasetSubtypes.put("specimen", "Specimen");
    datasetSubtypes.put("observation", "Observation");
    // mock getting the vocabulary
    when(mockVocabulariesManager.getI18nVocab(anyString(),anyString(), anyBoolean())).thenReturn(datasetSubtypes);

    // mock the cfg
    when(mockAppConfig.getBaseUrl()).thenReturn("http://localhost:7001/ipt");

    // construct ExtensionFactory using injected parameters
    Injector injector = Guice.createInjector(new ServletModule(), new Struts2GuicePluginModule(), new IPTModule());
    DefaultHttpClient httpClient = injector.getInstance(DefaultHttpClient.class);
    ThesaurusHandlingRule thesaurusRule = new ThesaurusHandlingRule(mock(VocabulariesManagerImpl.class));
    SAXParserFactory saxf = injector.getInstance(SAXParserFactory.class);
    ExtensionFactory extensionFactory = new ExtensionFactory(thesaurusRule, saxf, httpClient);

    // construct occurrence core Extension
    InputStream occurrenceCoreIs = ResourceManagerImplTest.class.getResourceAsStream("/extensions/dwc_occurrence.xml");
    Extension occurrenceCore = extensionFactory.build(occurrenceCoreIs);
    ExtensionManager extensionManager = mock(ExtensionManager.class);

    // mock ExtensionManager returning occurrence core Extension
    when(extensionManager.get("http://rs.tdwg.org/dwc/terms/Occurrence")).thenReturn(occurrenceCore);
    when(extensionManager.get("http://rs.tdwg.org/dwc/xsd/simpledarwincore/SimpleDarwinRecord")).thenReturn(occurrenceCore);

    ExtensionRowTypeConverter extensionRowTypeConverter = new ExtensionRowTypeConverter(extensionManager);
    ConceptTermConverter conceptTermConverter = new ConceptTermConverter(extensionRowTypeConverter);

    return new ResourceManagerImpl(mockAppConfig, mockedDataDir, mockEmailConverter, mockOrganisationKeyConverter,
        extensionRowTypeConverter, mockJdbcConverter, mockSourceManager, extensionManager,
        mockRegistryManager, conceptTermConverter, mockDwcaFactory, mockPasswordConverter, mockEml2Rtf,
        mockVocabulariesManager, mockSimpleTextProvider, mockRegistrationManager);
  }

  /**
   * test resource creation from zipped file.
   */
  @Test
  public void testCreateFromZippedFile()
    throws AlreadyExistingException, ImportException, SAXException, ParserConfigurationException, IOException {
    // retrieve sample zipped resource folder
    File resourceXML = FileUtils.getClasspathFile("resources/res1/resource.xml");
    // mock finding resource.xml file
    when(mockedDataDir.resourceFile(anyString(), anyString())).thenReturn(resourceXML);

    // retrieve sample zipped resource folder
    File emlXML = FileUtils.getClasspathFile("resources/res1/eml.xml");
    // mock finding eml.xml file
    when(mockedDataDir.resourceEmlFile(anyString(), anyInt())).thenReturn(emlXML);

    // create instance of manager
    ResourceManager resourceManager = getResourceManagerImpl();

    // retrieve sample zipped resource folder
    File zippedResourceFolder = FileUtils.getClasspathFile("resources/res1.zip");

    // create a new resource.
    resourceManager.create("res1", zippedResourceFolder, creator, baseAction);

    // test if new resource was added to the resources list.
    assertEquals(1, resourceManager.list().size());

    // get added resource.
    Resource res = resourceManager.get("res1");

    // test if resource was added correctly.
    assertEquals("res1", res.getShortname());
    assertEquals(creator, res.getCreator());
    assertEquals(creator, res.getModifier());

    // test if resource.xml was created.
    assertTrue(mockedDataDir.resourceFile("res1", ResourceManagerImpl.PERSISTENCE_FILE).exists());

    // properties that get preserved
    assertEquals(3, res.getEmlVersion());
    // there is 1 source file
    assertEquals(1, res.getSources().size());
    assertEquals("occurrence", res.getSources().get(0).getName());
    assertEquals(18, res.getSource("occurrence").getColumns());
    assertEquals(1, ((Source.FileSource) res.getSource("occurrence")).getIgnoreHeaderLines());
    assertEquals(15, ((Source.FileSource) res.getSource("occurrence")).getRows());

    // there is 1 mapping
    assertEquals(1, res.getMappings().size());
    assertEquals("occurrence", res.getMappings().get(0).getSource().getName());
    assertEquals(Constants.DWC_ROWTYPE_OCCURRENCE, res.getMappings().get(0).getExtension().getRowType());
    assertEquals(4, res.getMappings().get(0).getFields().size());
    assertEquals(0, res.getMappings().get(0).getIdColumn().intValue());

    // properties that get reset

    // the resource shouldn't be registered
    assertFalse(res.isRegistered());
    // the resource shouldn't have any managers
    assertEquals(0, res.getManagers().size());
    // the resource shouldn't have a last published date
    assertNull(res.getLastPublished());
    // the resource shouldn't be registered (no org, no key)
    assertNull(res.getKey());
    assertNull(res.getOrganisation());
    // the status should be private
    assertEquals(PublicationStatus.PRIVATE, res.getStatus());
    // the resource should have a created date
    assertNotNull(res.getCreated());
    // the num records published is 0
    assertEquals(0, res.getRecordsPublished());

    // eml properties loaded from eml.xml
    assertEquals("TEST RESOURCE", res.getEml().getTitle());
    assertEquals("Test description", res.getEml().getDescription());
  }

  /**
   * test resource creation from single DwC-A zipped file.
   */
  @Test
  public void testCreateFromSingleZippedFile()
    throws AlreadyExistingException, ImportException, SAXException, ParserConfigurationException, IOException {

    // create instance of manager
    ResourceManager resourceManager = getResourceManagerImpl();

    // retrieve sample DwC-A file
    File dwca = FileUtils.getClasspathFile("resources/occurrence.txt.zip");

    // create copy of DwC-A file in tmp dir, used to mock saving source resource filesource
    File tmpDir = FileUtils.createTempDir();
    List<File> files = CompressionUtil.decompressFile(tmpDir, dwca);
    File uncompressed = files.get(0);
    Source.FileSource fileSource = new Source.FileSource();
    fileSource.setFile(uncompressed);
    // it has 16 rows, plus 1 header line
    fileSource.setRows(16);
    fileSource.setIgnoreHeaderLines(1);
    fileSource.setEncoding("UTF-8");
    fileSource.setFieldsTerminatedByEscaped("/t");
    fileSource.setName("singleTxt");

    when(mockSourceManager.add(any(Resource.class), any(File.class), anyString())).thenReturn(fileSource);

    // create a new resource.
    resourceManager.create("res2", dwca, creator, baseAction);

    // test if new resource was added to the resources list.
    assertEquals(1, resourceManager.list().size());

    // get added resource.
    Resource res = resourceManager.get("res2");

    // test if resource was added correctly.
    assertEquals("res2", res.getShortname());
    assertEquals(creator, res.getCreator());
    assertEquals(creator, res.getModifier());

    // test if resource.xml was created.
    assertTrue(mockedDataDir.resourceFile("res2", ResourceManagerImpl.PERSISTENCE_FILE).exists());

    // properties that get preserved
    assertEquals(0, res.getEmlVersion());

    // note: source gets added to resource in sourceManager.add, and since we're mocking this call we can't set source

    // there is 1 mapping
    assertEquals(1, res.getMappings().size());
    assertEquals("singletxt", res.getMappings().get(0).getSource().getName());
    assertEquals(Constants.DWC_ROWTYPE_OCCURRENCE, res.getMappings().get(0).getExtension().getRowType());
    assertEquals(22, res.getMappings().get(0).getFields().size());
    assertEquals(0, res.getMappings().get(0).getIdColumn().intValue());

    // there are no eml properties except default shortname as title since there was no eml.xml file included
    assertEquals("res2", res.getEml().getTitle());
    assertEquals(null, res.getEml().getDescription());

    // properties that never get set on new resource creation

    // the resource shouldn't be registered
    assertFalse(res.isRegistered());
    // the resource shouldn't have any managers
    assertEquals(0, res.getManagers().size());
    // the resource shouldn't have a last published date
    assertNull(res.getLastPublished());
    // the resource shouldn't be registered (no org, no key)
    assertNull(res.getKey());
    assertNull(res.getOrganisation());
    // the status should be private
    assertEquals(PublicationStatus.PRIVATE, res.getStatus());
    // the resource should have a created date
    assertNotNull(res.getCreated());
    // the num records published is 0
    assertEquals(0, res.getRecordsPublished());
  }

  /**
   * test resource creation from zipped file, but resource.xml references non-existent extension.
   */
  @Test(expected=ImportException.class)
  public void testCreateFromZippedFileNonexistentExtension()
    throws AlreadyExistingException, ImportException, SAXException, ParserConfigurationException, IOException {
    // retrieve sample zipped resource folder
    File resourceXML = FileUtils.getClasspathFile("resources/res1/resource_nonexistent_ext.xml");
    // mock finding resource.xml file
    when(mockedDataDir.resourceFile(anyString(), anyString())).thenReturn(resourceXML);

    // create instance of manager
    ResourceManager resourceManager = getResourceManagerImpl();

    // retrieve sample zipped resource folder
    File zippedResourceFolder = FileUtils.getClasspathFile("resources/res1.zip");

    // create a new resource.
    resourceManager.create("res1", zippedResourceFolder, creator, baseAction);
  }

  /**
   * Test simple resource creation.
   */
  @Test
  public void testSimpleCreate()
    throws AlreadyExistingException, SAXException, ParserConfigurationException, IOException {
    ResourceManager resourceManager = getResourceManagerImpl();

    // create a new resource.
    resourceManager.create("math", creator);

    // test if new resource was added to the resources list.
    assertEquals(1, resourceManager.list().size());

    // get added resource.
    Resource addedResource = resourceManager.get("math");

    // test if resource was added correctly.
    assertEquals("math", addedResource.getShortname());
    assertEquals(creator, addedResource.getCreator());

    // test if resource.xml was created.
    assertTrue(mockedDataDir.resourceFile("math", ResourceManagerImpl.PERSISTENCE_FILE).exists());

  }

  @Test
  public void testInferCoreType() throws IOException, SAXException, ParserConfigurationException {
    ResourceManagerImpl manager = getResourceManagerImpl();
    // create test resource
    Resource resource = new Resource();
    // add mapping to taxon core
    ExtensionMapping mapping = new ExtensionMapping();
    Extension ext = new Extension();
    ext.setRowType(Constants.DWC_ROWTYPE_TAXON);
    mapping.setExtension(ext);
    resource.addMapping(mapping);

    resource = manager.inferCoreType(resource);
    // assert the coreType has now been correctly inferred
    assertEquals(Resource.CoreRowType.CHECKLIST.toString().toLowerCase(), resource.getCoreType().toLowerCase());
  }

  @Test
  public void testInferSubtype() throws IOException, SAXException, ParserConfigurationException {
    ResourceManagerImpl manager = getResourceManagerImpl();
    // create test resource
    Resource resource = new Resource();
    resource.setSubtype("unknown");
    resource = manager.standardizeSubtype(resource);
    // assert the subtype has been set to null, since it doesn't correspond to a known vocab term
    assertEquals(null, resource.getSubtype());

    resource.setSubtype("specimen");
    resource = manager.standardizeSubtype(resource);
    // assert the subtype has been set to "specimen", since it does correspond to the known vocab term "specimen"
    assertEquals("specimen", resource.getSubtype());
  }

  @Test
  public void testUpdateAlternateIdentifierForIPTURLToResource()
    throws IOException, SAXException, ParserConfigurationException {
    ResourceManagerImpl manager = getResourceManagerImpl();

    // create PRIVATE test resource
    Resource resource = new Resource();
    resource.setShortname("bees");
    Eml eml = new Eml();
    eml.setTitle("Bees of Kansas");
    eml.setAlternateIdentifiers(new LinkedList<String>());
    resource.setEml(eml);
    resource.setStatus(PublicationStatus.PRIVATE);

    // update alt. id
    manager.updateAlternateIdentifierForIPTURLToResource(resource);

    // update the alt. id - it should not have been set, since the resource is Private
    assertTrue(resource.getEml().getAlternateIdentifiers().size()==0);

    // change resource to PUBLIC
    resource.setStatus(PublicationStatus.PUBLIC);

    // update alt. id
    manager.updateAlternateIdentifierForIPTURLToResource(resource);
    // assert it has been set
    assertEquals("http://localhost:7001/ipt/resource.do?r=bees",
      resource.getEml().getAlternateIdentifiers().get(0));

    // change the baseURL now
    when(mockAppConfig.getBaseUrl()).thenReturn("http://192.38.28.24:7001/ipt");
    manager = new ResourceManagerImpl(mockAppConfig, mockedDataDir, mockEmailConverter, mockOrganisationKeyConverter,
      mock(ExtensionRowTypeConverter.class), mockJdbcConverter, mockSourceManager, mock(ExtensionManager.class),
      mockRegistryManager, mock(ConceptTermConverter.class), mockDwcaFactory, mockPasswordConverter, mockEml2Rtf,
      mockVocabulariesManager, mockSimpleTextProvider, mockRegistrationManager);

    // update alt. id
    manager.updateAlternateIdentifierForIPTURLToResource(resource);
    // assert it has been set
    assertEquals("http://192.38.28.24:7001/ipt/resource.do?r=bees",
      resource.getEml().getAlternateIdentifiers().get(0));

    // create PRIVATE test resource, with existing alt id
    resource.setStatus(PublicationStatus.PRIVATE);

    // update alt. id
    manager.updateAlternateIdentifierForIPTURLToResource(resource);

    // update the alt. id - it should disapear since the resource is Private now
    assertTrue(resource.getEml().getAlternateIdentifiers().size()==0);
  }

  @Test
  public void testUpdateAlternateIdentifierForRegistry()
    throws IOException, SAXException, ParserConfigurationException {
    ResourceManagerImpl manager = getResourceManagerImpl();

    // create PRIVATE test resource
    Resource resource = new Resource();
    resource.setShortname("bees");
    Eml eml = new Eml();
    eml.setTitle("Bees of Kansas");
    eml.setAlternateIdentifiers(new LinkedList<String>());
    resource.setEml(eml);
    resource.setStatus(PublicationStatus.PRIVATE);

    // update alt. id
    manager.updateAlternateIdentifierForRegistry(resource);
    // update the alt. id - it should not have been set, since the resource isn't registered yet
    assertTrue(resource.getEml().getAlternateIdentifiers().size()==0);

    // change resource to PUBLIC
    resource.setStatus(PublicationStatus.PUBLIC);
    // update alt. id
    manager.updateAlternateIdentifierForRegistry(resource);
    // update the alt. id - it should not have been set, since the resource isn't registered yet
    assertTrue(resource.getEml().getAlternateIdentifiers().size()==0);

    // change resource to Registered and give it a Registry UUID
    UUID key = UUID.randomUUID();
    resource.setKey(key);
    resource.setStatus(PublicationStatus.REGISTERED);

    // update alt. id
    manager.updateAlternateIdentifierForRegistry(resource);
    // assert it has been set
    assertEquals(key.toString(), resource.getEml().getAlternateIdentifiers().get(0));

    // try to update alt. id again
    manager.updateAlternateIdentifierForRegistry(resource);
    // there should still only be 1
    assertTrue(resource.getEml().getAlternateIdentifiers().size()==1);
  }

  @Test
  public void testRegisterMigratedResource() throws IOException, SAXException, ParserConfigurationException {
    ResourceManager manager = getResourceManagerImpl();

    String registeredDigirResourceUUID = "f9b67ad0-9c9b-11d9-b9db-b8a03c50a862";

    // indicate resource is migrated from DiGIR, by supplying the Registry UUID for the existing resource in the
    // resource's eml.alternateIdentifiers
    resource.getEml().getAlternateIdentifiers().add(registeredDigirResourceUUID);
    // indicate resource is ready to be published, by setting its status to Public
    resource.setStatus(PublicationStatus.PUBLIC);

    // mock returning list of resources that are associated to the Academy of Natural Sciences organization
    List<Resource> organisationsResources = new ArrayList<Resource>();
    Resource r1 = new Resource();
    r1.setKey(UUID.fromString(registeredDigirResourceUUID));
    r1.setTitle("Herpetology");
    organisationsResources.add(r1);

    when(mockRegistryManager.getOrganisationsResources(anyString())).thenReturn(organisationsResources);

    manager.register(resource, organisation, ipt, baseAction);

    // get registered resource.
    Resource registered = manager.get(resource.getShortname());

    assertEquals(PublicationStatus.REGISTERED, registered.getStatus());
    assertEquals(registeredDigirResourceUUID, registered.getKey().toString());
    assertEquals(organisation, registered.getOrganisation());
  }

  @Test(expected = InvalidConfigException.class)
  public void testRegisterMigratedResourceTooManyUUID() throws IOException, SAXException, ParserConfigurationException {
    ResourceManager manager = getResourceManagerImpl();

    String registeredDigirResourceUUID = "f9b67ad0-9c9b-11d9-b9db-b8a03c50a862";
    String extraUUID = "7615e6d1-9ebd-4302-9a7e-4913ca8b2bb4";

    resource.getEml().getAlternateIdentifiers().clear();
    // indicate resource is migrated from DiGIR, by supplying the Registry UUID for the existing resource in the
    // resource's eml.alternateIdentifiers
    resource.getEml().getAlternateIdentifiers().add(registeredDigirResourceUUID);
    // add the extra (unwanted) UUID to list of alternate identifiers - at most there should be 1 only before reg.
    resource.getEml().getAlternateIdentifiers().add(extraUUID);

    // indicate resource is ready to be published, by setting its status to Public
    resource.setStatus(PublicationStatus.PUBLIC);

    manager.register(resource, organisation, ipt, baseAction);
  }

  @Test(expected = InvalidConfigException.class)
  public void testRegisterMigratedResourceWithBadUUID() throws IOException, SAXException, ParserConfigurationException {
    ResourceManager manager = getResourceManagerImpl();

    // supply random UUID in the resource's eml.alternateIdentifiers that won't match one of organisation's resources
    resource.getEml().getAlternateIdentifiers().clear();
    resource.getEml().getAlternateIdentifiers().add(UUID.randomUUID().toString());
    // indicate resource is ready to be published, by setting its status to Public
    resource.setStatus(PublicationStatus.PUBLIC);

    // mock returning list of resources that are associated to the Academy of Natural Sciences organization
    List<Resource> organisationsResources = new ArrayList<Resource>();
    Resource r1 = new Resource();
    // resource has different UUID than the one in the alternate identifiers list - interpreted as failed migration
    r1.setKey(UUID.fromString(UUID.randomUUID().toString()));
    r1.setTitle("Herpetology");
    organisationsResources.add(r1);

    when(mockRegistryManager.getOrganisationsResources(anyString())).thenReturn(organisationsResources);

    manager.register(resource, organisation, ipt, baseAction);
  }
}