package uk.ac.cam.cl.dtg.segue.configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

import ma.glasnost.orika.MapperFacade;

import org.apache.commons.lang3.Validate;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.cam.cl.dtg.segue.api.Constants;
import uk.ac.cam.cl.dtg.segue.api.ContentVersionController;
import uk.ac.cam.cl.dtg.segue.api.UserManager;
import uk.ac.cam.cl.dtg.segue.auth.AuthenticationProvider;
import uk.ac.cam.cl.dtg.segue.auth.FacebookAuthenticator;
import uk.ac.cam.cl.dtg.segue.auth.GoogleAuthenticator;
import uk.ac.cam.cl.dtg.segue.auth.IFederatedAuthenticator;
import uk.ac.cam.cl.dtg.segue.auth.TwitterAuthenticator;
import uk.ac.cam.cl.dtg.segue.dao.ContentMapper;
import uk.ac.cam.cl.dtg.segue.dao.GitContentManager;
import uk.ac.cam.cl.dtg.segue.dao.IAppDataManager;
import uk.ac.cam.cl.dtg.segue.dao.IContentManager;
import uk.ac.cam.cl.dtg.segue.dao.ILogManager;
import uk.ac.cam.cl.dtg.segue.dao.IUserDataManager;
import uk.ac.cam.cl.dtg.segue.dao.LogManager;
import uk.ac.cam.cl.dtg.segue.dao.MongoAppDataManager;
import uk.ac.cam.cl.dtg.segue.dao.MongoUserDataManager;
import uk.ac.cam.cl.dtg.segue.database.GitDb;
import uk.ac.cam.cl.dtg.segue.database.Mongo;
import uk.ac.cam.cl.dtg.segue.dos.content.Choice;
import uk.ac.cam.cl.dtg.segue.dos.content.ChoiceQuestion;
import uk.ac.cam.cl.dtg.segue.dos.content.Content;
import uk.ac.cam.cl.dtg.segue.dos.content.Figure;
import uk.ac.cam.cl.dtg.segue.dos.content.Image;
import uk.ac.cam.cl.dtg.segue.dos.content.Quantity;
import uk.ac.cam.cl.dtg.segue.dos.content.Question;
import uk.ac.cam.cl.dtg.segue.dos.content.SeguePage;
import uk.ac.cam.cl.dtg.segue.dos.content.Video;
import uk.ac.cam.cl.dtg.segue.search.ElasticSearchProvider;
import uk.ac.cam.cl.dtg.segue.search.ISearchProvider;
import uk.ac.cam.cl.dtg.util.PropertiesLoader;

import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.mongodb.DB;

/**
 * This class is responsible for injecting configuration values for persistence
 * related classes.
 * 
 */
public class SegueGuiceConfigurationModule extends AbstractModule {
	private static final Logger log = LoggerFactory
			.getLogger(SegueGuiceConfigurationModule.class);

	// we only ever want there to be one instance of each of these.
	private static ContentMapper mapper = null;
	private static ContentVersionController contentVersionController = null;
	private static GitContentManager contentManager = null;
	private static Client elasticSearchClient = null;
	private static UserManager userManager = null;

	private static GoogleClientSecrets googleClientSecrets = null;

	private PropertiesLoader globalProperties = null;

	/**
	 * Create a SegueGuiceConfigurationModule.
	 */
	public SegueGuiceConfigurationModule() {
		try {
			globalProperties = new PropertiesLoader(
					"/config/segue-config.properties");

		} catch (IOException e) {
			log.error("Error loading properties file.", e);
		}
	}

	@Override
	protected void configure() {
		try {
			this.configureProperties();
			this.configureDataPersistence();
			this.configureSegueSearch();
			this.configureSecurity();
			this.configureApplicationManagers();

		} catch (IOException e) {
			e.printStackTrace();
			log.error("IOException during setup process.");
		}
	}

	/**
	 * Extract properties and bind them to constants.
	 */
	private void configureProperties() {
		// Properties loader
		bind(PropertiesLoader.class).toInstance(globalProperties);

		this.bindConstantToProperty(Constants.SEARCH_CLUSTER_NAME,
				globalProperties);
		this.bindConstantToProperty(Constants.SEARCH_CLUSTER_ADDRESS,
				globalProperties);
		this.bindConstantToProperty(Constants.SEARCH_CLUSTER_PORT,
				globalProperties);
	}

	/**
	 * Configure all things persistency.
	 * 
	 * @throws IOException
	 *             - when we cannot load the database.
	 */
	private void configureDataPersistence() throws IOException {
		// Setup different persistence bindings
		// MongoDb
		bind(DB.class).toInstance(Mongo.getDB());

		// GitDb
		bind(GitDb.class)
				.toInstance(
						new GitDb(
								globalProperties
										.getProperty(Constants.LOCAL_GIT_DB),
								globalProperties
										.getProperty(Constants.REMOTE_GIT_SSH_URL),
								globalProperties
										.getProperty(Constants.REMOTE_GIT_SSH_KEY_PATH)));
	}

	/**
	 * Configure segue search classes.
	 */
	private void configureSegueSearch() {
		bind(ISearchProvider.class).to(ElasticSearchProvider.class);
	}

	/**
	 * Configure user security related classes.
	 */
	private void configureSecurity() {
		this.bindConstantToProperty(Constants.HMAC_SALT, globalProperties);

		// Configure security providers
		// Google
		this.bindConstantToProperty(Constants.GOOGLE_CLIENT_SECRET_LOCATION,
				globalProperties);
		this.bindConstantToProperty(Constants.GOOGLE_CALLBACK_URI,
				globalProperties);
		this.bindConstantToProperty(Constants.GOOGLE_OAUTH_SCOPES,
				globalProperties);
		
		// Facebook
		this.bindConstantToProperty(Constants.FACEBOOK_SECRET,
				globalProperties);
		this.bindConstantToProperty(Constants.FACEBOOK_CLIENT_ID,
				globalProperties);
		this.bindConstantToProperty(Constants.FACEBOOK_CALLBACK_URI,
				globalProperties);
		this.bindConstantToProperty(Constants.FACEBOOK_OAUTH_SCOPES,
				globalProperties);
		
		// Twitter
		this.bindConstantToProperty(Constants.TWITTER_SECRET,
				globalProperties);
		this.bindConstantToProperty(Constants.TWITTER_CLIENT_ID,
				globalProperties);
		this.bindConstantToProperty(Constants.TWITTER_CALLBACK_URI,
				globalProperties);

		// Register a map of security providers
		MapBinder<AuthenticationProvider, IFederatedAuthenticator> mapBinder = MapBinder
				.newMapBinder(binder(), AuthenticationProvider.class,
						IFederatedAuthenticator.class);
		mapBinder.addBinding(AuthenticationProvider.GOOGLE).to(
				GoogleAuthenticator.class);
		mapBinder.addBinding(AuthenticationProvider.FACEBOOK).to(
				FacebookAuthenticator.class);
		mapBinder.addBinding(AuthenticationProvider.TWITTER).to(
				TwitterAuthenticator.class);

	}

	/**
	 * Deals with application data managers.
	 */
	private void configureApplicationManagers() {
		// Allows Mongo to take over Content Management
		// bind(IContentManager.class).to(MongoContentManager.class);
		
		// Allows GitDb to take over content Management 
		bind(IContentManager.class).to(GitContentManager.class); 

		//TODO: the log manager needs redoing.
		bind(ILogManager.class).to(LogManager.class);

		bind(IUserDataManager.class).to(MongoUserDataManager.class);
	}

	/**
	 * This provides a singleton of the elasticSearch client that can be used by
	 * Guice.
	 * 
	 * The client is threadsafe so we don't need to keep creating new ones.
	 * 
	 * @param clusterName
	 *            - The name of the cluster to create.
	 * @param address
	 *            - address of the cluster to create.
	 * @param port
	 *            - port of the custer to create.
	 * @return Client to be injected into ElasticSearch Provider.
	 */
	@Inject
	@Provides
	@Singleton
	private static Client getSearchConnectionInformation(
			@Named(Constants.SEARCH_CLUSTER_NAME) final String clusterName,
			@Named(Constants.SEARCH_CLUSTER_ADDRESS) final String address,
			@Named(Constants.SEARCH_CLUSTER_PORT) final int port) {
		if (null == elasticSearchClient) {
			elasticSearchClient = ElasticSearchProvider.getTransportClient(
					clusterName, address, port);
			log.info("Creating singleton of ElasticSearchProvider");
		}

		return elasticSearchClient;
	}

	/**
	 * This provides a singleton of the contentVersionController for the segue
	 * facade.
	 * 
	 * @param properties
	 *            - properties loader
	 * @param contentManager
	 *            - content manager (with associated persistence links).
	 * @return Content version controller with associated dependencies.
	 */
	@Inject
	@Provides
	@Singleton
	private static ContentVersionController getContentVersionController(
			final PropertiesLoader properties,
			final IContentManager contentManager) {
		if (null == contentVersionController) {
			contentVersionController = new ContentVersionController(properties,
					contentManager);
			log.info("Creating singleton of ContentVersionController");
		}
		return contentVersionController;
	}
	
	/**
	 * This provides a singleton of the git content manager for the segue
	 * facade.
	 * 
	 * @param database - database reference
	 * @param searchProvider - search provider to use
	 * @param contentMapper - content mapper to use.
	 * @return a fully configured content Manager.
	 */
	@Inject
	@Provides
	@Singleton
	private GitContentManager getContentManager(final GitDb database,
			final ISearchProvider searchProvider,
			final ContentMapper contentMapper) {		
		if (null == contentManager) {
			contentManager = new GitContentManager(database, searchProvider, contentMapper);
			log.info("Creating singleton of ContentManager");
		}

		return contentManager;
	}	

	/**
	 * This provides a singleton of the contentVersionController for the segue
	 * facade.
	 * 
	 * @return Content version controller with associated dependencies.
	 */
	@Inject
	@Provides
	@Singleton
	private ContentMapper getContentMapper() {
		if (null == mapper) {
			mapper = new ContentMapper();
			this.buildDefaultJsonTypeMap();
		}

		return mapper;
	}

	/**
	 * This provides a singleton of the contentVersionController for the segue
	 * facade.
	 * 
	 * @param database
	 *            - IUserManager
	 * @param hmacSalt
	 *            - the salt for the hmac
	 * @param providersToRegister
	 *            - list of known providers.
	 * @return Content version controller with associated dependencies.
	 */
	@Inject
	@Provides
	@Singleton
	private static UserManager getUserManager(
			final IUserDataManager database,
			@Named(Constants.HMAC_SALT) final String hmacSalt,
			final Map<AuthenticationProvider, IFederatedAuthenticator> providersToRegister) {

		if (null == userManager) {
			userManager = new UserManager(database, hmacSalt,
					providersToRegister);
			log.info("Creating singleton of UserManager");
		}

		return userManager;
	}

	/**
	 * Gets the instance of the dozer mapper object.
	 * 
	 * @return a preconfigured instance of an Auto Mapper. This is specialised
	 *         for mapping SegueObjects.
	 */
	@Provides
	@Singleton
	@Inject
	private MapperFacade getDozerDOtoDTOMapper() {
		return this.getContentMapper().getAutoMapper();
	}

	/**
	 * Gets the instance of the dozer mapper object.
	 * 
	 * @param clientSecretLocation
	 *            - The path to the client secrets json file
	 * @return GoogleClientSecrets
	 * @throws IOException
	 *             - when we are unable to access the google client file.
	 */
	@Provides
	@Singleton
	@Inject
	private static GoogleClientSecrets getGoogleClientSecrets(
			@Named(Constants.GOOGLE_CLIENT_SECRET_LOCATION) final String clientSecretLocation)
		throws IOException {
		if (null == googleClientSecrets) {
			Validate.notNull(clientSecretLocation, "Missing resource %s",
					clientSecretLocation);

			// load up the client secrets from the file system.
			InputStream inputStream = new FileInputStream(clientSecretLocation);
			InputStreamReader isr = new InputStreamReader(inputStream);

			googleClientSecrets = GoogleClientSecrets.load(
					new JacksonFactory(), isr);
		}

		return googleClientSecrets;
	}

	/**
	 * This method will pre-register the mapper class so that content objects
	 * can be mapped.
	 * 
	 * It requires that the class definition has the JsonType("XYZ") annotation
	 */
	private void buildDefaultJsonTypeMap() {
		// We need to pre-register different content objects here for the
		// auto-mapping to work
		mapper.registerJsonTypeAndDTOMapping(Content.class);
		mapper.registerJsonTypeAndDTOMapping(SeguePage.class);
		mapper.registerJsonTypeAndDTOMapping(Choice.class);
		mapper.registerJsonTypeAndDTOMapping(Quantity.class);
		mapper.registerJsonTypeAndDTOMapping(Question.class);
		mapper.registerJsonTypeAndDTOMapping(ChoiceQuestion.class);
		mapper.registerJsonTypeAndDTOMapping(Image.class);
		mapper.registerJsonTypeAndDTOMapping(Figure.class);
		mapper.registerJsonTypeAndDTOMapping(Video.class);
	}

	/**
	 * Utility method to make the syntax of property bindings clearer.
	 * 
	 * @param propertyLabel
	 *            - Key for a given property
	 * @param propertyLoader
	 *            - property loader to use
	 */
	private void bindConstantToProperty(final String propertyLabel,
			final PropertiesLoader propertyLoader) {
		bindConstant().annotatedWith(Names.named(propertyLabel)).to(
				propertyLoader.getProperty(propertyLabel));
	}

	/**
	 * Segue utility method for providing a new instance of an application
	 * manager.
	 * 
	 * @param databaseName
	 *            - the database / table name - should be unique.
	 * @param classType
	 *            - the class type that represents what can be managed by this
	 *            app manager.
	 * @param <T>
	 *            the type that can be managed by this App Manager.
	 * @return the application manager ready to use.
	 */
	public static <T> IAppDataManager<T> getAppDataManager(
			final String databaseName, final Class<T> classType) {
		// for now this only returns mongodb typed objects.
		return new MongoAppDataManager<T>(Mongo.getDB(), databaseName,
				classType);
	}

}
