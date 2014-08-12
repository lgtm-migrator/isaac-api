package uk.ac.cam.cl.dtg.segue.dao.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.mongojack.JacksonDBCollection;
import org.mongojack.WriteResult;

import com.google.inject.Inject;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;

import uk.ac.cam.cl.dtg.segue.api.Constants.BooleanOperator;
import uk.ac.cam.cl.dtg.segue.dos.content.Content;
import uk.ac.cam.cl.dtg.segue.dto.ResultsWrapper;
import uk.ac.cam.cl.dtg.segue.dto.content.ContentDTO;

/**
 * Implementation that specifically works with MongoDB Content objects.
 * 
 */
public class MongoContentManager implements IContentManager {

	private final DB database;
	private final ContentMapper mapper;

	/**
	 * MongoDB Adapter for Content objects.
	 * 
	 * @param database
	 *            - the mongodb client.
	 * @param mapper
	 *            - an instance of the content mapper class.
	 */
	@Inject
	public MongoContentManager(final DB database, final ContentMapper mapper) {
		this.database = database;
		this.mapper = mapper;
	}

	@Override
	public <T extends Content> String save(final T objectToSave) {
		JacksonDBCollection<T, String> jc = JacksonDBCollection.wrap(database.getCollection("content"),
				getContentSubclass(objectToSave), String.class);
		WriteResult<T, String> r = jc.save(objectToSave);
		return r.getSavedId().toString();
	}

	@Override
	public Content getById(final String id, final String version) {
		if (null == id) {
			return null;
		}

		// version parameter is unused in this particular implementation
		DBCollection dbCollection = database.getCollection("content");

		// Do database query using plain mongodb so we only have to read from
		// the database once.
		DBObject node = dbCollection.findOne(new BasicDBObject("id", id));

		Content c = mapper.mapDBOjectToContentDO(node);

		return c;
	}

	@Override
	public List<String> listAvailableVersions()  {
		throw new UnsupportedOperationException("MongoDB Content Manager does not support this operation.");
	}
	
	/**
	 * Gets a the correct content sub class for the object passed in.
	 * @param <T> the sub class of content.
	 * @param obj - object to get the correct subclass of.
	 * @return the sub class
	 */
	@SuppressWarnings("unchecked")
	private <T extends Content> Class<T> getContentSubclass(final T obj) {
		if (obj instanceof Content) {
			return (Class<T>) obj.getClass();			
		}

		throw new IllegalArgumentException("object is not a subtype of Content");
	}

	@Override
	public ByteArrayOutputStream getFileBytes(final String version, final String filename) throws IOException {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public String getLatestVersionId() {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public void clearCache() {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public ResultsWrapper<ContentDTO> getContentByTags(final String version, final Set<String> tags) {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public ResultsWrapper<ContentDTO> searchForContent(final String version, final String searchString,
			final Map<String, List<String>> typesToInclude) {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public Set<String> getTagsList(final String version) {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public Set<String> getAllUnits(final String version) {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public Set<String> getCachedVersionList() {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public void clearCache(final String version) {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public boolean isValidVersion(final String version) {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public boolean ensureCache(final String version) {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public int compareTo(final String version1, final String version2) {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public Map<Content, List<String>> getProblemMap(final String version) {
		throw new UnsupportedOperationException("MongoDB Content Manager does not support this operation.");
	}

	@Override
	public ResultsWrapper<ContentDTO> findByFieldNames(final String version,
			final Map<Entry<BooleanOperator, String>, List<String>> fieldsToMatch, 
			final Integer startIndex, final Integer limit) {
		throw new UnsupportedOperationException("MongoDB Content Manager does not support this operation.");
	}

	@Override
	public ResultsWrapper<ContentDTO> findByFieldNamesRandomOrder(final String version,
			final Map<Entry<BooleanOperator, String>, List<String>> fieldsToMatch, 
			final Integer startIndex, final Integer limit) {
		throw new UnsupportedOperationException("MongoDB Content Manager does not support this operation.");
	}

	@Override
	public ResultsWrapper<ContentDTO> getByIdPrefix(final String idPrefix, final String version) {
		throw new UnsupportedOperationException("MongoDB Content Manager does not support this operation.");
	}

	@Override
	public ContentDTO populateContentSummaries(final String version, final ContentDTO contentDTO) {
		throw new UnsupportedOperationException("This method is not implemented yet.");
	}

	@Override
	public void setIndexRestriction(final boolean loadOnlyPublishedContent) {
		throw new UnsupportedOperationException("MongoDB Content Manager does not support this operation.");
	}
}
