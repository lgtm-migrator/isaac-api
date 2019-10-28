package uk.ac.cam.cl.dtg.isaac.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.util.Lists;
import com.google.inject.Inject;
import com.mongodb.util.JSONParseException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.jboss.resteasy.annotations.GZIP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.cam.cl.dtg.isaac.dto.TestDTO;
import uk.ac.cam.cl.dtg.segue.api.managers.QuestionManager;
import uk.ac.cam.cl.dtg.segue.api.managers.UserAccountManager;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.NoUserLoggedInException;
import uk.ac.cam.cl.dtg.segue.dao.ILogManager;
import uk.ac.cam.cl.dtg.segue.dao.content.ContentMapper;
import uk.ac.cam.cl.dtg.segue.dos.QuestionValidationResponse;
import uk.ac.cam.cl.dtg.segue.dos.content.ChoiceQuestion;
import uk.ac.cam.cl.dtg.segue.dos.content.Content;
import uk.ac.cam.cl.dtg.segue.dto.SegueErrorResponse;
import uk.ac.cam.cl.dtg.segue.dto.users.RegisteredUserDTO;
import uk.ac.cam.cl.dtg.segue.quiz.IValidator;
import uk.ac.cam.cl.dtg.segue.quiz.ValidatorUnavailableException;
import uk.ac.cam.cl.dtg.util.PropertiesLoader;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.List;

// TODO documentation
@Path("/tests")
@Api(value = "/tests")
public class TestsFacade extends AbstractIsaacFacade {
    private static final Logger log = LoggerFactory.getLogger(PagesFacade.class);

    private final UserAccountManager userManager;
    private final ContentMapper mapper;

    @Inject
    public TestsFacade(final PropertiesLoader propertiesLoader, final ILogManager logManager,
                       final UserAccountManager userManager, final ContentMapper mapper) {
        super(propertiesLoader, logManager);
        this.userManager = userManager;
        this.mapper = mapper;
    }

    @POST
    @Path("/{questionType}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @GZIP
    @ApiOperation(value = "Create a new user or update an existing user.")
    public Response createOrUpdateUserSettings(@Context final HttpServletRequest request,
                                               @Context final HttpServletResponse response,
                                               @PathParam("questionType") final String questionType,
                                               final String testJson) {
        try {
            // User authorisation
            RegisteredUserDTO currentUser = userManager.getCurrentRegisteredUser(request);
            if (!isUserStaff(userManager, currentUser)) {
                return SegueErrorResponse.getIncorrectRoleResponse();
            }

            ObjectMapper sharedContentMapper = mapper.getSharedContentObjectMapper();
            TestDTO test = sharedContentMapper.readValue(testJson, TestDTO.class);

            // Create a fake question
            Class<? extends Content> questionClass = mapper.getClassByType(questionType);
            if (null == questionClass || ChoiceQuestion.class.isAssignableFrom(questionClass)) {
                throw new BadRequestException(String.format("Not a valid questionType (%s)", questionType));
            }
            ChoiceQuestion testQuestion = (ChoiceQuestion) questionClass.newInstance();
            testQuestion.setChoices(test.getChoices());

            IValidator questionValidator = QuestionManager.locateValidator(testQuestion.getClass());
            if (null == questionValidator) {
                throw new ValidatorUnavailableException("Could not find a validator for the question");
            }

            // For each test, check its results against the fake question
            List<TestDTO.TestCaseDTO> results = Lists.newArrayList();
            for (TestDTO.TestCaseDTO testCase : test.getCases()) {
                QuestionValidationResponse questionValidationResponse =
                        questionValidator.validateQuestionResponse(testQuestion, testCase.getChoice());
                testCase.setActual(questionValidationResponse.isCorrect());
                testCase.setExplanation(questionValidationResponse.getExplanation());
                results.add(testCase);
            }

            return Response.ok(results).build();

        } catch (NoUserLoggedInException e) {
            return SegueErrorResponse.getNotLoggedInResponse();
        } catch (ValidatorUnavailableException | IOException e) {
            return SegueErrorResponse.getServiceUnavailableResponse(e.getMessage());
        } catch (JSONParseException | InstantiationException | IllegalAccessException e) {
            return SegueErrorResponse.getBadRequestResponse(e.getMessage());
        }
    }
}
