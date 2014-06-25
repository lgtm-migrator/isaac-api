package uk.ac.cam.cl.dtg.isaac.models.pages;

import java.util.List;

import uk.ac.cam.cl.dtg.segue.dto.content.Content;
import uk.ac.cam.cl.dtg.segue.dto.content.ContentSummary;

public class ContentPage {
	private String id;
	private Content contentObject;
	private List<ContentSummary> sidebarContent;

	public ContentPage(String id, Content contentObject,
			List<ContentSummary> sidebarContent) {
		super();
		this.id = id;
		this.contentObject = contentObject;
		this.sidebarContent = sidebarContent;

	}

	public String getId() {
		return id;
	}

	public Content getContentObject() {
		return contentObject;
	}

	public List<ContentSummary> getSidebarContent() {
		return sidebarContent;
	}
}
