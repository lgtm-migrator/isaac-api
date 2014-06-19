package uk.ac.cam.cl.dtg.isaac.app;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.mail.MessagingException;
import javax.mail.internet.AddressException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.lang3.StringUtils;
import org.dozer.Mapper;
import org.jboss.resteasy.annotations.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.cam.cl.dtg.isaac.configuration.IsaacGuiceConfigurationModule;
import uk.ac.cam.cl.dtg.isaac.models.pages.ContentPage;
import uk.ac.cam.cl.dtg.segue.api.SegueApiFacade;
import uk.ac.cam.cl.dtg.segue.api.SegueGuiceConfigurationModule;
import uk.ac.cam.cl.dtg.segue.dto.ResultsWrapper;
import uk.ac.cam.cl.dtg.segue.dto.SegueErrorResponse;
import uk.ac.cam.cl.dtg.segue.dto.content.Content;
import uk.ac.cam.cl.dtg.segue.dto.content.ContentSummary;
import uk.ac.cam.cl.dtg.segue.dto.content.Image;
import uk.ac.cam.cl.dtg.util.Mailer;
import uk.ac.cam.cl.dtg.util.PropertiesLoader;

import com.google.api.client.util.Maps;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Guice;
import com.google.inject.Injector;

import static uk.ac.cam.cl.dtg.segue.api.Constants.*;
import static uk.ac.cam.cl.dtg.isaac.app.Constants.*;

/**
 * Rutherford Controller
 * 
 * This class specifically caters for the Rutherford physics server and is expected to provide extended functionality to the Segue api for use only on the Rutherford site.
 * 
 */
@Path("/")
public class IsaacController {
	private static final Logger log = LoggerFactory.getLogger(IsaacController.class);
	
	private static SegueApiFacade api;
	private static PropertiesLoader propertiesLoader;
	
	private static GameManager gameManager;

	public IsaacController(){
		// Get an instance of the segue api so that we can service requests directly from it 
		// without using the rest endpoints.
		if(null == api){
			Injector injector = Guice.createInjector(new IsaacGuiceConfigurationModule(), new SegueGuiceConfigurationModule());
			api = injector.getInstance(SegueApiFacade.class);
			propertiesLoader = injector.getInstance(PropertiesLoader.class);
			gameManager = new GameManager(api);
		}
		
//		test of user registration - this is just a snippet for future reference as I didn't know where else to put it.
//		User user = api.getCurrentUser(req);
//		// example of requiring user to be logged in.
//		if(null == user)
//			return api.authenticationInitialisation(req, "google");
//		else
//			log.info("User Logged in: " + user.getEmail());
	}
	
	
	@GET
	@Path("pages/concepts")
	@Produces("application/json")
	public Response getConceptList(@Context HttpServletRequest req,
			@QueryParam("tags") String tags, @QueryParam("start_index") String startIndex, @QueryParam("limit") String limit) {		
		
		Map<String,List<String>> fieldsToMatch = Maps.newHashMap();
		fieldsToMatch.put(TYPE_FIELDNAME, Arrays.asList(CONCEPT_TYPE));
		
		// options
		if(null != tags)
			fieldsToMatch.put(TAGS_FIELDNAME, Arrays.asList(tags));
		
		return listContentObjects(fieldsToMatch, startIndex, limit);
	}
	
	
	@GET
	@Path("pages/concepts/{concept_page_id}")
	@Produces("application/json")
	public Response getConcept(@Context HttpServletRequest req,
			@PathParam("concept_page_id") String conceptId) {
		Map<String,List<String>> fieldsToMatch = Maps.newHashMap();
		fieldsToMatch.put(TYPE_FIELDNAME, Arrays.asList(CONCEPT_TYPE));

		// options
		if(null != conceptId)
			fieldsToMatch.put(ID_FIELDNAME + "." + UNPROCESSED_SEARCH_FIELD_SUFFIX, Arrays.asList(conceptId));
		
		return this.findSingleResult(fieldsToMatch);
	}

	@GET
	@Path("pages/questions")
	@Produces("application/json")
	public Response getQuestionList(@Context HttpServletRequest req,
			@QueryParam("tags") String tags, @QueryParam("level") String level, @QueryParam("start_index") String startIndex, @QueryParam("limit") String limit) {		
		
		Map<String,List<String>> fieldsToMatch = Maps.newHashMap();
		fieldsToMatch.put(TYPE_FIELDNAME, Arrays.asList(QUESTION_TYPE));

		// options
		if(null != tags)
			fieldsToMatch.put(TAGS_FIELDNAME, Arrays.asList(tags));
		if(null != level)
			fieldsToMatch.put(LEVEL_FIELDNAME, Arrays.asList(level));
		
		return listContentObjects(fieldsToMatch, startIndex, limit);
	}	
	
	@GET
	@Path("pages/questions/{question_page_id}")
	@Produces("application/json")
	public Response getQuestion(@Context HttpServletRequest req,
			@PathParam("question_page_id") String questionId) {
		Map<String,List<String>> fieldsToMatch = Maps.newHashMap();
		fieldsToMatch.put("type", Arrays.asList(QUESTION_TYPE));

		// options
		if(null != questionId)
			fieldsToMatch.put(ID_FIELDNAME + "." + UNPROCESSED_SEARCH_FIELD_SUFFIX, Arrays.asList(questionId));

		return this.findSingleResult(fieldsToMatch);
	}	

	@GET
	@Path("gameboards")
	@Produces("application/json")	
	public Response generateGameboard(@Context HttpServletRequest req, @QueryParam("tags") String tags, @QueryParam("level") String level){
		return Response.ok(gameManager.generateRandomGameboard(level, tags)).build();
	}
	
	@GET
	@Path("pages/{page}")
	@Produces("application/json")
	public Response getPage(@Context HttpServletRequest req,
			@PathParam("page") String page) {
		Content c = (Content) api.getContentById(api.getLiveVersion(), page).getEntity(); // this endpoint can be used to get any content object

		if(null == c){
			return new SegueErrorResponse(Status.NOT_FOUND, "Unable to locate the content requested.").toResponse();
		}
		
		String proxyPath = propertiesLoader.getProperty(Constants.PROXY_PATH);
		ContentPage cp = new ContentPage(c.getId(),c,this.buildMetaContentmap(proxyPath, c));		
		
		return Response.ok(cp).build();
	}
	
	@GET
	@Produces("*/*")
	@Path("images/{path:.*}")
	@Cache
	public Response getImageByPath(@PathParam("path") String path) {	
		return api.getImageFileContent(api.getLiveVersion(), path);
	}
	
//	@POST
//	@Consumes({"application/x-www-form-urlencoded"})
//	@Path("contact-us/sendContactUsMessage")
	public ImmutableMap<String,String> postContactUsMessage(
			@FormParam("full-name") String fullName,
			@FormParam("email") String email,
			@FormParam("subject") String subject,
			@FormParam("message-text") String messageText,
			@Context HttpServletRequest request){
		
		// construct a new instance of the mailer object
		Mailer contactUsMailer = new Mailer(propertiesLoader.getProperty(Constants.MAILER_SMTP_SERVER), propertiesLoader.getProperty(Constants.MAIL_FROM_ADDRESS));
		
		if (StringUtils.isBlank(fullName) && StringUtils.isBlank(email) && StringUtils.isBlank(subject) && StringUtils.isBlank(messageText)){
			log.debug("Contact us required field validation error ");
			return ImmutableMap.of("result", "message not sent - Missing required field - Validation Error");			
		}
		
		// Get IpAddress of client
		String ipAddress = request.getHeader("X-FORWARDED-FOR");
		
		if (ipAddress == null) {
			ipAddress = request.getRemoteAddr();
		}

		// Construct message
		StringBuilder message = new StringBuilder();
		message.append("- Sender Details - " + "\n");
		message.append("From: " + fullName + "\n");
		message.append("E-mail: " + email + "\n");
		message.append("IP address: " + ipAddress + "\n");
		message.append("Message Subject: " + subject + "\n");
		message.append("- Message - " + "\n");
		message.append(messageText);
		
		try {
			// attempt to send the message via the smtp server
			contactUsMailer.sendMail(propertiesLoader.getProperty(Constants.MAIL_RECEIVERS).split(","), email, subject, message.toString());
			log.info("Contact Us - E-mail sent to " + propertiesLoader.getProperty(Constants.MAIL_RECEIVERS) + " " + email + " " + subject + " " + message.toString());
			
		} catch (AddressException e) {				
			log.warn("E-mail Address validation error " + e.toString());
			return ImmutableMap.of(
					"result", "message not sent - E-mail address malformed - Validation Error \n " + e.toString());		
			
		} catch (MessagingException e) {
			log.error("Messaging error " + e.toString());
			return ImmutableMap.of(
					"result", "message not sent - Unknown Messaging error\n " + e.toString());	
		}
		
		return ImmutableMap.of("result", "success");
	}	

	
	/**
	 * This method will look at a content objects related content list and return a list of contentInfo objects which can be used for creating links etc.
	 * 
	 * This method returns null if the content object provided has no related Content
	 * 
	 * @param proxyPath - the string prefix for the server being used
	 * @param content - the content object which contains related content
	 * @return
	 */
	private List<ContentSummary> buildMetaContentmap(String proxyPath, Content content){
		if(null == content){
			return null;
		}else if(content.getRelatedContent() == null || content.getRelatedContent().isEmpty()){
			return null;
		}
		
		List<ContentSummary> contentInfoList = new ArrayList<ContentSummary>();
		
		for(String id : content.getRelatedContent()){
			try{
				Content relatedContent = (Content) api.getContentById(api.getLiveVersion(), id).getEntity();
				
				if(relatedContent == null){
					log.warn("Related content (" + id + ") does not exist in the data store.");
				} else {
					ContentSummary contentInfo = extractContentInfo(relatedContent, proxyPath);
					contentInfoList.add(contentInfo);
				}
			}
			catch(ClassCastException exception){
				log.error("Error whilst trying to cast one object to another.",exception);
				// TODO: fix how SegueErrorResponse exception objects are handled - they clearly cannot be cast as content objects here.
			}
		}
		
		return contentInfoList;
	}

	/**
	 * Generate a URI that will enable us to find an object again.
	 * 
	 * @param content
	 * @returns null if we are unable to generate the URL or a string that represents the url combined with any proxypath information required.
	 */
	public static String generateApiUrl(Content content){		
		Injector injector = Guice.createInjector(new IsaacGuiceConfigurationModule(), new SegueGuiceConfigurationModule());
		String proxyPath = injector.getInstance(PropertiesLoader.class).getProperty(Constants.PROXY_PATH);
		
		String resourceUrl = null;
		try{
			if(content instanceof Image){
				resourceUrl = proxyPath + "/api/images/" + URLEncoder.encode(content.getId(), "UTF-8");
			}
			// TODO fix this stuff to be less horrid
			else if(content.getType().toLowerCase().contains("question")){
				resourceUrl = proxyPath + "/api/questions/" + URLEncoder.encode(content.getId(), "UTF-8");
			}
			else if(content.getType().toLowerCase().contains("concept")){
				resourceUrl = proxyPath + "/api/concepts/" + URLEncoder.encode(content.getId(), "UTF-8");
			}
			else{
				resourceUrl = proxyPath + "/api/pages/" + URLEncoder.encode(content.getId(), "UTF-8");
			}	
		}
		catch(UnsupportedEncodingException e){
			log.error("Url generation for resource id " + content.getId() + " failed. ", e);
		}
		
		return resourceUrl;
	}
	
	/**
	 * This method will extract basic information from a content object so the lighter ContentInfo object can be sent to the client instead.
	 * 
	 * @param content
	 * @param proxyPath
	 * @return
	 */
	private ContentSummary extractContentInfo(Content content, String proxyPath){
		if (null == content)
			return null;

		// try automapping with dozer
		Injector injector = Guice.createInjector(new IsaacGuiceConfigurationModule(), new SegueGuiceConfigurationModule());
		Mapper mapper = injector.getInstance(Mapper.class);
		
		ContentSummary contentInfo = mapper.map(content, ContentSummary.class);
		contentInfo.setUrl(generateApiUrl(content));
		
		return contentInfo;
	}

	/**
	 * Utility method to convert a list of content objects into a list of ContentInfo Objects
	 *  
	 * @param contentList
	 * @param proxyPath
	 * @return list of shorter contentInfo objects.
	 */
	private List<ContentSummary> extractContentInfo(List<Content> contentList, String proxyPath){
		if (null == contentList)
			return null;
		
		List<ContentSummary> listOfContentInfo = new ArrayList<ContentSummary>();
		
		for(Content content : contentList){
			ContentSummary contentInfo = extractContentInfo(content,proxyPath); 
			if(null != contentInfo)
				listOfContentInfo.add(contentInfo);
		}		
		return listOfContentInfo;
	}
	
	/**
	 * For use when we expect to only find a single result.
	 * 
	 * @param req
	 * @param fieldsToMatch
	 * @return A Response containing a single conceptPage or an error.
	 */
	private Response findSingleResult(Map<String,List<String>> fieldsToMatch){		
		ResultsWrapper<Content> conceptList = api.findMatchingContent(api.getLiveVersion(), fieldsToMatch, null, null); // includes type checking.
		Content c = null;
		if(conceptList.getResults().size() > 1){
			return new SegueErrorResponse(Status.INTERNAL_SERVER_ERROR, "Multiple results (" + conceptList.getResults().size() + ") returned error. For search query: " + fieldsToMatch.values()).toResponse();
		}
		else if(conceptList.getResults().isEmpty()){
			return new SegueErrorResponse(Status.NOT_FOUND, "No content found that matches the query with parameters: " + fieldsToMatch.values()).toResponse();
		}
		else{
			c = conceptList.getResults().get(0);
		}
		
		String proxyPath = propertiesLoader.getProperty(Constants.PROXY_PATH);
		ContentPage cp = new ContentPage(c.getId(),c,this.buildMetaContentmap(proxyPath, c));		

		return Response.ok(cp).build();		
	}
	
	private Response listContentObjects(Map<String,List<String>> fieldsToMatch, String startIndex, String limit){
		ResultsWrapper<Content> c;
		try {
			Integer resultsLimit = null;
			Integer startIndexOfResults = null;
			
			if(null != limit){
				resultsLimit = Integer.parseInt(limit);
			}
			
			if(null != startIndex)
			{
				startIndexOfResults = Integer.parseInt(startIndex);
			}
			
	        c = api.findMatchingContent(api.getLiveVersion(), fieldsToMatch, startIndexOfResults, resultsLimit);
	        
		} catch(NumberFormatException e) { 
	    	return new SegueErrorResponse(Status.BAD_REQUEST, "Unable to convert one of the integer parameters provided into numbers (null is ok). Params provided were: limit " + limit + " and startIndex " + startIndex, e).toResponse();
	    }
		
		ResultsWrapper<ContentSummary> summarizedContent = new ResultsWrapper<ContentSummary>(this.extractContentInfo(c.getResults(), propertiesLoader.getProperty(Constants.PROXY_PATH)), c.getTotalResults());		
		
		return Response.ok(summarizedContent).build();
	}
}
