package connectors.dao;

import java.util.List;

import models.generator.Configurations;

/**
 * Abstract class implementing {@link AlignmentDao}.
 * 
 * Useful to include limitations on sources and attributes
 * @author federico
 *
 */
public abstract class AlignmentAbstractDao implements AlignmentDao {
	private List<String> sourceNames;
	private List<String> excludedAttributes;
	
	/**
	 * @param sourceNames limit to those sources
	 * @param excludedAttributes exclude those attributes
	 */
	public AlignmentAbstractDao(List<String> sourceNames, List<String> excludedAttributes) {
		super();
		this.sourceNames = sourceNames;
		this.excludedAttributes = excludedAttributes;
	}
	
	public AlignmentAbstractDao(Configurations conf) {
		this(conf.getWebsitesOrdered(), conf.getExcludedAttributes());
	}

	protected List<String> getSourceNames() {
		return sourceNames;
	}

	protected List<String> getExcludedAttributes() {
		return excludedAttributes;
	}
	
}
