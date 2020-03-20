package connectors.dao;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;

import connectors.MongoDbConnectionFactory;
import connectors.MongoDbUtils;
import model.AbstractProductPage.Specifications;
import model.Source;
import model.SourceProductPage;
import models.generator.Configurations;
import utils.UrlUtils;

public class MongoAlignmentDao extends AlignmentAbstractDao {

	private MongoDatabase database;

	public MongoAlignmentDao(Configurations conf, MongoDbConnectionFactory mongoConnector) {
		super(conf);
		this.database = mongoConnector.getDatabase();
	}

	@Override
	public List<SourceProductPage> getSamplePagesFromCategory(int size, String category) {
		// uses sample method of MongoDB
		MongoCollection<Document> collection = this.database.getCollection(MongoDbUtils.PRODUCTS_COLLECTION_NAME);
		List<SourceProductPage> sample = new ArrayList<>();
		Bson eqFilter = Filters.eq(MongoDbUtils.CATEGORY, category);
		Bson neFilterS = Filters.ne(MongoDbUtils.SPECS, new Document());
		
		Bson linkageFilter;
		if (getSourceNames() == null) {
			linkageFilter = Filters.ne(MongoDbUtils.LINKAGE, Collections.EMPTY_LIST);
		} else {
			String regex = "(.*" + String.join(".*)|(.*", getSourceNames()) + ".*)";
		//	String regex = getSourceNames().stream().map(str -> "/.*"+ str + ".*/").collect(Collectors.toList());
			linkageFilter = Filters.regex(MongoDbUtils.LINKAGE, regex);
		}
		Bson andFilter;
		if (category.equals("all"))
			andFilter = Filters.and(neFilterS, linkageFilter);
		else
			andFilter = Filters.and(neFilterS, eqFilter);
		andFilter = returnFilterAddingSourceNamesFilter(getSourceNames(), andFilter);
		Bson sampleBson = Aggregates.sample(size);
		Bson matchBson = Aggregates.match(andFilter);
		collection.aggregate(Arrays.asList(matchBson, sampleBson))
				.forEach((Document d) -> sample.add(MongoDbUtils.convertDocumentToProductPage(d, getExcludedAttributes())));

		return sample;
	}

	/**
	 * Add source filter to existing filter
	 * @param sourceNames
	 * @param andFilter
	 * @return
	 */
	private Bson returnFilterAddingSourceNamesFilter(List<String> sourceNames, Bson andFilter) {
		if (sourceNames != null) {
			Bson sourceFilters = Filters.in(MongoDbUtils.WEBSITE, sourceNames);
			andFilter = Filters.and(andFilter, sourceFilters);
		}
		return andFilter;
	}

	@Override
	public Map<Source, List<String>> getSchemas(List<String> categories) {
		Map<Source, List<String>> fetchedSchemas = new TreeMap<>();

		this.database.getCollection(MongoDbUtils.SCHEMAS_COLLECTION)
				.find(returnFilterAddingSourceNamesFilter(getSourceNames(), Filters.and(Filters.in(MongoDbUtils.CATEGORY, categories),
						Filters.ne(MongoDbUtils.ATTRIBUTES, Collections.EMPTY_LIST))))
				.forEach((Document d) -> {
					Source source = new Source(d.getString(MongoDbUtils.CATEGORY), d.getString(MongoDbUtils.WEBSITE));
					@SuppressWarnings("unchecked")
					List<String> attributes = d.get(MongoDbUtils.ATTRIBUTES, List.class);
					attributes.removeAll(getExcludedAttributes());
					fetchedSchemas.put(source, attributes);
				});

		return fetchedSchemas;
	}

	@Override
	public List<String> getSingleSchema(Source source) {
		Bson wFilter = Filters.eq(MongoDbUtils.WEBSITE, source.getWebsite());
		Bson cFilter = Filters.eq(MongoDbUtils.CATEGORY, source.getCategory());
		Bson andFilter = Filters.and(wFilter, cFilter);

		@SuppressWarnings("unchecked")
		List<String> fetchedSchema = this.database.getCollection(MongoDbUtils.SCHEMAS_COLLECTION).find(andFilter)
				.first().get(MongoDbUtils.ATTRIBUTES, List.class);
		fetchedSchema.removeAll(getExcludedAttributes());
		return fetchedSchema;
	}

	// TODO extract some parts
	@Override
	public Map<SourceProductPage, List<SourceProductPage>> getProdsInRL(
			List<String> catalogWebsites, String category) {
		Map<SourceProductPage, List<SourceProductPage>> rlMap = new HashMap<>();
		MongoCollection<Document> collection = this.database.getCollection(MongoDbUtils.PRODUCTS_COLLECTION_NAME);

		Bson wFilter = Filters.in(MongoDbUtils.WEBSITE, catalogWebsites);
		Bson cFilter = Filters.eq(MongoDbUtils.CATEGORY, category);
		Bson sFilter = Filters.ne(MongoDbUtils.SPECS, new Document());
		Bson lFilter = Filters.ne(MongoDbUtils.LINKAGE, Collections.EMPTY_LIST);
		Bson andFilter = Filters.and(wFilter, cFilter, sFilter, lFilter);
		Bson matchBson = Aggregates.match(andFilter);
		Bson unwindBson = Aggregates.unwind("$linkage");

		// get all product's pages in "catalog" with linkage (unwinding the
		// linkage attribute)
		List<Document> prods = new ArrayList<>();
		collection.aggregate(Arrays.asList(matchBson, unwindBson)).forEach((Document d) -> prods.add(d));

		// create map <URL of a linked page, page in catalog>
		Map<String, Document> linkageUrls = new HashMap<>();
		prods.stream().forEach(p -> linkageUrls.put(p.getString(MongoDbUtils.LINKAGE), p));

		// get all the linked product's pages
		// <url, list of document in linkage with the one associated to the key>
		Map<String, List<SourceProductPage>> intL = new HashMap<>();
		// <linked page, page in catalog>
		Map<Document, Document> extL = new HashMap<>();
		Bson uFilter = Filters.in(MongoDbUtils.URL, linkageUrls.keySet());
		
		andFilter = returnFilterAddingSourceNamesFilter(getSourceNames(), Filters.and(cFilter, uFilter, sFilter));
		collection.find(andFilter)
				.projection(Projections.include(MongoDbUtils.SPECS, MongoDbUtils.URL, MongoDbUtils.WEBSITE))
				.forEach((Document p) -> {
					// if it's not a page in the
					// catalog create new map
					// entry
					if (!catalogWebsites.contains(UrlUtils.getDomain(p.getString(MongoDbUtils.URL)))) {
						Document catalogPage = linkageUrls.get(p.getString(MongoDbUtils.URL));
						extL.put(p, catalogPage);
					}
					// if it's a page in the
					// catalog add it to the
					// temporary
					// list
					else {
						Document rlDoc = linkageUrls.get(p.getString(MongoDbUtils.URL));
						String urlP = p.getString(MongoDbUtils.URL);
						String urlRlDoc = linkageUrls.get(p.getString(MongoDbUtils.URL)).getString(MongoDbUtils.URL);
						List<SourceProductPage> linkageP = intL.getOrDefault(urlP, new ArrayList<SourceProductPage>());
						List<SourceProductPage> linkageRlDoc = intL.getOrDefault(urlRlDoc,
								new ArrayList<SourceProductPage>());
						// TODO The linkage attribute here is "wrong" (unwinded) but it is not used
						// anymore
						// so it should be ok. However it is not a nice solution
						linkageP.add(MongoDbUtils.convertUnwindedDocumentToProductPage(rlDoc, getExcludedAttributes()));
						linkageRlDoc.add(MongoDbUtils.convertDocumentToProductPage(p, getExcludedAttributes()));
						intL.put(urlP, linkageP);
						intL.put(urlRlDoc, linkageRlDoc);
					}
				});

		// add internal linkage to the lists in rlMap
		for (Map.Entry<Document, Document> entry : extL.entrySet()) {
			List<SourceProductPage> linkageList = new ArrayList<>();
			if (intL.containsKey(entry.getValue().getString(MongoDbUtils.URL)))
				linkageList = intL.get(entry.getValue().getString(MongoDbUtils.URL));
			else
				linkageList.add(MongoDbUtils.convertUnwindedDocumentToProductPage(entry.getValue(), getExcludedAttributes()));
			rlMap.put(MongoDbUtils.convertDocumentToProductPage(entry.getKey(), getExcludedAttributes()), linkageList);
		}

		// <linked page, list of pages in catalog>
		return rlMap;
	}

	@Override
	public List<SourceProductPage> getPagesLinkedWithSource2filtered(String category, String website2,
			String attribute) {
		MongoCollection<Document> collection = this.database.getCollection(MongoDbUtils.PRODUCTS_COLLECTION_NAME);
		List<SourceProductPage> prods = new ArrayList<>();
		Bson cFilter = Filters.eq(MongoDbUtils.CATEGORY, category);
		Pattern regex = Pattern.compile(".*" + website2.replace(".", "\\.") + ".*", Pattern.CASE_INSENSITIVE);
		Bson lFilter = Filters.eq(MongoDbUtils.LINKAGE, regex);
		Bson aFilter = Filters.exists(MongoDbUtils.SPECS + "." + attribute, true);

		Bson andFilter = returnFilterAddingSourceNamesFilter(getSourceNames(), Filters.and(cFilter, aFilter, lFilter));

		collection.find(andFilter).forEach((Document d) -> prods.add(MongoDbUtils.convertDocumentToProductPage(d, getExcludedAttributes())));

		return prods;
	}

	@Override
	public List<Entry<Specifications, SourceProductPage>> getPairsOfPagesInLinkage(List<SourceProductPage> prods,
			String website, String attribute) {
		Map<String, List<Integer>> rlMap = new HashMap<>();
		List<String> docsToFetch = new ArrayList<>();
		List<SourceProductPage> fetchedProducts = new ArrayList<>();
		List<Entry<Specifications, SourceProductPage>> rlList = new ArrayList<>();

		// get urls of the linkage products
		for (int i = 0; i < prods.size(); i++) {
			SourceProductPage p = prods.get(i);
			for (String url : p.getLinkage())
				if (url.contains(website)) {
					List<Integer> indexes = rlMap.getOrDefault(url, new ArrayList<Integer>());
					indexes.add(i);
					rlMap.put(url, indexes);
					docsToFetch.add(url);
				}
		}

		// get all the linkage products containing the relevant attribute
		MongoCollection<Document> collection = this.database.getCollection(MongoDbUtils.PRODUCTS_COLLECTION_NAME);
		Bson inFilter = Filters.in(MongoDbUtils.URL, docsToFetch);
		Bson attFilter = Filters.exists(MongoDbUtils.SPECS + "." + attribute, true);
		Bson andFilter = Filters.and(inFilter, attFilter);
		collection.find(andFilter).projection(Projections.include(MongoDbUtils.SPECS, MongoDbUtils.URL))
				.forEach((Document d) -> fetchedProducts.add(MongoDbUtils.convertDocumentToProductPage(d, getExcludedAttributes())));

		// create record linkage list
		fetchedProducts.stream().forEach(d -> {
			List<Integer> indexes = rlMap.get(d.getUrl());
			for (int i : indexes)
				rlList.add(new AbstractMap.SimpleEntry<>(prods.get(i).getSpecifications(), d));
		});
		return rlList;
	}

	@Override
	public SourceProductPage getPageFromUrlIfExistsInDataset(String url) {
		Document firstResult = this.database.getCollection(MongoDbUtils.PRODUCTS_COLLECTION_NAME)
				.find(returnFilterAddingSourceNamesFilter(getSourceNames(), 
						Filters.and(Filters.eq(MongoDbUtils.URL, url),
						Filters.ne(MongoDbUtils.LINKAGE, Collections.EMPTY_LIST),
						Filters.ne(MongoDbUtils.SPECS, new Document()))))
				.first();
		SourceProductPage res = null;
		if (firstResult != null) {
			res = MongoDbUtils.convertDocumentToProductPage(firstResult, getExcludedAttributes());
		}
		return res;
	}
}
