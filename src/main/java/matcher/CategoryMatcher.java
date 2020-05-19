package matcher;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import connectors.RConnector;
import connectors.dao.AlignmentDao;
import model.AbstractProductPage.Specifications;
import model.SourceProductPage;
import models.generator.Configurations;
import models.matcher.DataFrame;
import models.matcher.Features;
import models.matcher.InvertedIndexesManager;
import models.matcher.Match;
import models.matcher.Schema;

/**
 * Class that actually computes the schema alignment using the classifier
 * 
 * @author marco
 *
 */
public class CategoryMatcher {

	private AlignmentDao dao;
	private RConnector r;
	private FeaturesBuilder fb;

	public CategoryMatcher(AlignmentDao dao, RConnector r, Configurations conf) {
		this.dao = dao;
		this.r = r;
		this.fb = new FeaturesBuilder(conf);
	}

	/**
	 * Makes union of N sources in catalogWebsites, merging pages in linkage, then find matches with an external source 
	 * 
	 * @param websites
	 *            should be provided in linkage order. First is used as catalog,
	 *            then for each following websites, attributes are affected to
	 *            catalog attributes (or added as new attributes if they don't match
	 *            to any other att TODO matchToOne ??)
	 * @param category
	 * @param cardinality
	 * @param schemaMatch
	 * @param useMI
	 * @param matchToOne
	 * @return
	 */
	public boolean alignAttributeSchema(List<String> catalogWebsites, String newSource, String category, Schema schemaMatch, boolean useMI,
			boolean deleteIfNotInCatalog) {
		boolean matched = false;
		// linked page -> pages in catalog
		Map<SourceProductPage, List<SourceProductPage>> pagesInSource2allLinkedPagesInCatalog = this.dao.getProdsInRL(catalogWebsites, category);
		// check if new source is matchable
		if (checkIfValidWebsite(newSource, pagesInSource2allLinkedPagesInCatalog.keySet())) {
			// specifications in catalog(merged) -> linked page (adding to attribute name the name of website, [att_name###website])
			List<Entry<Specifications, SourceProductPage>> linkedProds = setupCatalog(pagesInSource2allLinkedPagesInCatalog, 
					schemaMatch, deleteIfNotInCatalog);

			// Inverted indexes (attribute name -> indexes of linked pages outside catalog)
			InvertedIndexesManager invIndexes = getInvertedIndexes(linkedProds, newSource);

			Map<String, Integer> attributesLinkage = new HashMap<>();
			DataFrame dataFrame = computeAttributesFeatures(linkedProds, invIndexes, newSource,
					attributesLinkage);
			try {
				double[] predictions = r.classify(dataFrame);
				Match match = detectBestAttributeMatchesFromPrediction(dataFrame, predictions);

				updateSchema(schemaMatch, invIndexes, match, attributesLinkage, deleteIfNotInCatalog);

				matched = true;
			} catch (Exception e) {
				System.err.println("Errore durante la classificazione: "+e.getMessage());
				e.printStackTrace();
				System.out.println("CON LINKAGE -> " + checkIfValidWebsite(newSource, pagesInSource2allLinkedPagesInCatalog.keySet()));
				System.out.println("(df di lunghezza : " + dataFrame.getAttrCatalog().size() + ")");
			}
		}

		// if false the match was skipped
		return matched;
	}

	/**
	 * check if among the linked pages there's at least one belonging to the new website, otherwise it is useless to add it
	 * @param website
	 * @param linkedPages
	 * @return
	 */
	boolean checkIfValidWebsite(String website, Set<SourceProductPage> linkedPages) {
		boolean foundSourcePage = false;

		for (SourceProductPage page : linkedPages) {
			if (page.getSource().getWebsite().equals(website)) {
				foundSourcePage = true;
				break;
			}
		}

		return foundSourcePage;
	}

	/**
	 * Takes as input map pagesInSource2allLinkedPagesInCatalog<p>
	 * For each group of pages from the sources catalog in linkage, builds a single page. Attribute names will be replaced
	 * with name of attribute in catalog.
	 * <p>
	 * Eg: suppose S1 = [brand, color, size], S2 = [brand, size, price], S3 = [brand].
	 * S1 and S2 compose the catalog, and we already detected matches between S1,S2 in previous iterations.<p>
	 * The catalog attributes are then the ones from S1 (brand, color, size),
	 *  plus S2.price as it did not match with any attribute in S1 (note that if deleteIfNotInCatalog is true, then S2.price is not added).<p>
	 * Then here we would have in input pages like S3.p1 --> [S1.p1, S2.p1], so we need to merge S1.p1 and S2.p1 in a single spec.<br/>
	 * Attributes S2.brand and S2.size would be renamed into S1.brand and S2.size (as we know they match), while S2.price would keep this name.
	 * <p>
	 * After renaming, if more attributes with same name exists, their values are merged separating them with '###'
	 * 
	 * 
	 * Afterwards, it creates a list of entries [merged_spec, page_out_catalog]
	 * 
	 * @param pagesInSource2allLinkedPagesInCatalog
	 * @param schema
	 * @return
	 */
	private List<Entry<Specifications, SourceProductPage>> setupCatalog(
			Map<SourceProductPage, List<SourceProductPage>> pagesInSource2allLinkedPagesInCatalog, Schema schema, boolean deleteIfNotInCatalog) {
		return setupCatalog(pagesInSource2allLinkedPagesInCatalog, schema, "", deleteIfNotInCatalog);
	}

	private List<Entry<Specifications, SourceProductPage>> setupCatalog(
			Map<SourceProductPage, List<SourceProductPage>> pagesInSource2allLinkedPagesInCatalog, Schema schema, String reference, boolean deleteIfNotInCatalog) {
		List<Entry<Specifications, SourceProductPage>> updatedList = new LinkedList<Entry<Specifications, SourceProductPage>>();

		for (Map.Entry<SourceProductPage, List<SourceProductPage>> pageSourceEntry2linkedCatalogPages : pagesInSource2allLinkedPagesInCatalog.entrySet()) {
			SourceProductPage sourcePage = pageSourceEntry2linkedCatalogPages.getKey();
			Specifications specList = mergeAttributeInPages(pageSourceEntry2linkedCatalogPages.getValue(), schema, reference, deleteIfNotInCatalog);
			updateSpecs(sourcePage, schema);
			updatedList.add(new AbstractMap.SimpleEntry<Specifications, SourceProductPage>(specList, sourcePage));
		}

		return updatedList;
	}

	/**
	 * Takes as input a set of specs pertaining to the catalog.
	 * 
	 * Creates a single specification built in this way<ul>
	 * <li> One attribute for each distinct attribute name in specs.
	 * <li> Value is a concatenation of all existing values, with ### as separator.
	 * </ul>
	 * 
	 * @param groupOfLinkedPagesInCatalog
	 * @param schema
	 * @param reference
	 * @return
	 */
	private Specifications mergeAttributeInPages(List<SourceProductPage> groupOfLinkedPagesInCatalog, Schema schema, String reference, 
			boolean deleteIfNotInSpecs) {
		// update the attribute names according to the existing schema
		if (!deleteIfNotInSpecs) {
			groupOfLinkedPagesInCatalog.forEach(p -> updateSpecs(p, schema));
		}
		Specifications newSpecs = new Specifications();

		for (SourceProductPage p : groupOfLinkedPagesInCatalog)
			for (Entry<String, String> attr2value : p.getSpecifications().entrySet()) {
				/*
				 * if reference is an empty string add all attributes if it contains a source
				 * name, only add the attributes that can be found in it
				 */
				String attr = attr2value.getKey();
				if (reference.equals("") || attr.contains(reference)) {
					String oldValue = (String) newSpecs.getOrDefault(attr, "");
					String newValue = attr2value.getValue();
					if (oldValue.length() > 0) // there was already a value for that attr
						newSpecs.put(attr, oldValue + "###" + newValue);
					else
						newSpecs.put(attr, newValue);
				}
			}
		
		if (deleteIfNotInSpecs) {
			//eventually all different attribute values are merged, if we use a single catalog.
			newSpecs.replaceAll((name, values) -> mergeValues(values));;
		}
		return newSpecs;
	}

	/**
	 * Put most frequent value
	 * @param values
	 * @return
	 */
	private String mergeValues(String values) {
		if (values.length() == 0) {
			return values;
		}
		
		List<String> allValues = Arrays.asList(values.split("###"));
		Collections.sort(allValues);
		String mostRepeatedWord = allValues.stream()
	          .collect(Collectors.groupingBy(w -> w, Collectors.counting()))
	          .entrySet()
	          .stream()
	          .max(Comparator.comparing(Entry::getValue))
	          .get()
	          .getKey();
		return mostRepeatedWord;
	}

	/**
	 * Update spec of a product page, replacing attribute names with catalog mapped attribute name. <br/>
	 * 
	 * Use the format attribute###website
	 * 
	 * @param p
	 * @param schema
	 */
	private void updateSpecs(SourceProductPage p, Schema schema) {
		Map<String, String> specifications = p.getSpecifications();
		Map<String, String> newSpecs = new HashMap<>();
		String website = p.getSource().getWebsite();
		// update attribute names to the format "attribute###website"
		specifications.keySet().forEach(attr -> {
			if (!attr.contains("###")) {
				String value = specifications.get(attr);
				newSpecs.put(schema.getAttributesMap().getOrDefault(attr + "###" + website, attr + "###" + website),
						value);
			} else
				/*
				 * In case of wrong linkage a page could appear twice in the original
				 * linkageMap; when this happens, its specs get updated twice, but the second
				 * time they need to be added without changes.
				 */
				newSpecs.put(attr, specifications.get(attr));
		});
		specifications.clear();
		specifications.putAll(newSpecs);
	}

	private InvertedIndexesManager getInvertedIndexes(List<Entry<Specifications, SourceProductPage>> prods,
			String website) {
		Map<String, Set<Integer>> invIndCatalog = new HashMap<>();
		Map<String, Set<Integer>> invIndLinked = new HashMap<>();
		Map<String, Set<Integer>> invIndSource = new HashMap<>();

		// check all linked product pages
		for (int i = 0; i < prods.size(); i++) {
			Entry<Specifications, SourceProductPage> pair = prods.get(i);
			// get the attributes present in those 2 pages
			Set<String> attrsCatalog = pair.getKey().keySet();
			Set<String> attrsLinked = pair.getValue().getSpecifications().keySet();
			// check the website of the linked page
			boolean isInSource = pair.getValue().getSource().getWebsite().equals(website);

			// add the attributes in the catalog's index
			for (String attrC : attrsCatalog) {
				Set<Integer> indexes = invIndCatalog.getOrDefault(attrC, new HashSet<Integer>());
				indexes.add(i);
				invIndCatalog.put(attrC, indexes);
			}
			// add the attributes in the linked index...
			for (String attrL : attrsLinked) {
				Set<Integer> indexesL = invIndLinked.getOrDefault(attrL, new HashSet<Integer>());
				indexesL.add(i);
				invIndLinked.put(attrL, indexesL);

				// ...and the source index if the page belongs to the source to
				// be matched
				if (isInSource) {
					Set<Integer> indexesS = invIndSource.getOrDefault(attrL, new HashSet<Integer>());
					indexesS.add(i);
					invIndSource.put(attrL, indexesS);
				}
			}
		}

		InvertedIndexesManager invIndexes = new InvertedIndexesManager();
		invIndexes.setCatalogIndex(invIndCatalog);
		invIndexes.setLinkedIndex(invIndLinked);
		invIndexes.setSourceIndex(invIndSource);

		return invIndexes;
	}

	/**
	 * Given all linked pairs of merged specification in catalog + specification outside, returns 
	 * for all pairs a1 (in C) - a2 (outside C) a score of match
	 * 
	 * @param linkedProds
	 * @param invIndexes
	 * @param website
	 * @param attributesLinkage
	 * @return
	 */
	private DataFrame computeAttributesFeatures(List<Entry<Specifications, SourceProductPage>> linkedProds,
			InvertedIndexesManager invIndexes, String website, Map<String, Integer> attributesLinkage) {

		DataFrame df = new DataFrame();
		// Set<String> attrSet = new TreeSet<>();

		// scorri il prod cartesiano di attributiS x attributiC
		for (Map.Entry<String, Set<Integer>> attrS : invIndexes.getSourceIndex().entrySet())
			for (Map.Entry<String, Set<Integer>> attrCatalog : invIndexes.getCatalogIndex().entrySet()) {
				String attributeCatalog = attrCatalog.getKey();
				String attributeSource = attrS.getKey();

				// prods in linkage between S and Catalog with the required
				// attributes
				Set<Integer> commonPagesS = new HashSet<>(attrS.getValue());
				commonPagesS.retainAll(attrCatalog.getValue());
				if (commonPagesS.size() < 1)
					continue;
				// prods in linkage between the whole category and Catalog with
				// the required attributes
				Set<Integer> commonProdsL = new HashSet<>(invIndexes.getLinkedIndex().get(attributeSource));
				commonProdsL.retainAll(attrCatalog.getValue());

				List<Entry<Specifications, SourceProductPage>> linkageS = new ArrayList<>();
				commonPagesS.forEach(i -> linkageS.add(linkedProds.get(i)));

				List<Entry<Specifications, SourceProductPage>> linkageL = new ArrayList<>();
				commonProdsL.forEach(i -> linkageL.add(linkedProds.get(i)));

				attributesLinkage.put(attributeSource + attributeCatalog, linkageS.size());
				Features features = this.fb.computeFeatures(linkageS, linkageL, 
						attributeCatalog, attributeSource, null);
				df.addRow(features, attributeCatalog, attributeSource);
			}

		// System.out.println("Considerati: " + attrSet.size());
		// System.out.println(attrSet.toString());

		return df;
	}

	/**
	 * Given match probability for pairs of matches, select good pairs and bad
	 * pairs:
	 * <ul>
	 * <li>If match < 0.5, discards it
	 * <li>If an attribute has matches with different catalog atts, select the best
	 * match (the one with highest match probabilty) and discards the others.
	 * </ul>
	 * 
	 * @param df
	 * @param predictions
	 * @return
	 */
	private Match detectBestAttributeMatchesFromPrediction(DataFrame df, double[] predictions) {
		Match match = new Match();
		List<Double> filteredPredictions = new ArrayList<>();

		// df.updateMatchProbabilities(predictions);
		// remove all matches where the probability is below 0.5
		filteredPredictions = filterFalse(df, predictions);

		// FileDataConnector fdc = new FileDataConnector();
		// fdc.printDataFrame("dataFrame(card5)", df.toCSVFormatSlim());

		// get only the best (unique) matches
		boolean repeat = false;
		do {
			repeat = getMaxMatches(df, match, filteredPredictions);
		} while (repeat);

		return match;
	}

	/**
	 * Remove all matches where the probability is below 0.5
	 * @param df
	 * @param predictions
	 * @return
	 */
	private List<Double> filterFalse(DataFrame df, double[] predictions) {
		//TODO this is written in a REALLY REALLY complicated and useless way, refactor. 
		List<Double> predictionsList = new ArrayList<>();
		List<Integer> indexes = IntStream.range(0, predictions.length).mapToObj(Integer.class::cast)
				.collect(Collectors.toList());

		// Remove from indexes all index of predictions > 0.5, while adding their value to predictions
		predictionsList = IntStream.range(0, predictions.length).filter(i -> predictions[i] >= 0.5).mapToObj(i -> {
			indexes.remove(indexes.indexOf(i));
			return predictions[i];
		}).collect(Collectors.toList());

		// indexes.stream().forEach(i -> {
		// if(df.getAttrCatalog().get(i).equals(df.getAttrSource().get(i)))
		// System.out.println(df.getAttrSource().get(i)+"---->"+df.getAttrCatalog().get(i)
		// +"-----_>"+predictions[i]);
		// });
		
		// remove from dataframe predictions in indexes, i.e. predictions with prob < 0.5 
		
		Collections.reverse(indexes);
		df.removeByIndexes(indexes);

		// return the predictions with prob > 0.5
		return predictionsList;
	}

	/**
	 * ??? not clear and very confuse. I think the principle is to return, for each attribute, the most probable att in catalog
	 * 
	 * @param df
	 * @param match
	 * @param predictions
	 * @return
	 */
	private boolean getMaxMatches(DataFrame df, Match match, List<Double> predictions) {
		Map<String, Integer> indexes = new HashMap<>();
		List<Integer> ranges = df.getSourceRanges();

		for (int i = 0; i < ranges.size() - 1; i++) {
			// select only the probability of matches for the specific Source
			// Attribute
			List<Double> probs = predictions.subList(ranges.get(i), ranges.get(i + 1));
			// get the best match probability
			double max = Collections.max(probs);
			boolean isMultiple = Collections.frequency(probs, max) > 1;
			// check if there aren't more than one optimal match and if so,
			// accept it
			if (!isMultiple) {
				int index = ranges.get(i) + probs.indexOf(max);
				String catalogAttribute = df.getStringAtIndex("attrCatalog", index);
				// check that there isn't already a Source Attribute matched to
				// the same Catalog Attribute
				if (predictions.get(index) >= predictions.get(indexes.getOrDefault(catalogAttribute, index)))
					indexes.put(catalogAttribute, index);
			}
		}

		TreeSet<Integer> rowsToRemove = new TreeSet<>();
		for (int index : indexes.values()) {
			String sourceAttr = df.getStringAtIndex("attrSource", index);
			String catalogAttr = df.getStringAtIndex("attrCatalog", index);
			double p = predictions.get(index);
			// add Match
			match.addRow(catalogAttr, sourceAttr, p);
			rowsToRemove.addAll(df.getIndexesByValue("attrSource", sourceAttr));
			rowsToRemove.addAll(df.getIndexesByValue("attrCatalog", catalogAttr));
		}

		rowsToRemove = (TreeSet<Integer>) rowsToRemove.descendingSet();
		// remove all rows in the dataframe containing one of the two attributes
		df.removeByIndexes(new ArrayList<>(rowsToRemove));
		// also remove the probabilities associated to those rows
		// https://stackoverflow.com/questions/4524347/java-arraylist-remove-problem/46916235 !!!!
		rowsToRemove.stream().forEach(i -> predictions.remove((int) i));

		return indexes.values().size() > 0;
	}

	/**
	 * Insert pairs of attributes in schema.<br/>
	 * The update consists in adding every attribute found in the matched source
	 * (even those not matched) and new attributes from the catalog (if there are)
	 *
	 * @param schema
	 * @param invIndexes
	 * @param match
	 * @param attributesLinkage
	 * @param deleteIfNotInCatalog 
	 */
	public void updateSchema(Schema schema, InvertedIndexesManager invIndexes, Match match,
			Map<String, Integer> attributesLinkage, boolean deleteIfNotInCatalog) {

		// add new catalog's attribute
		for (String catAttr : invIndexes.getCatalogIndex().keySet())
			if (!schema.getAttributesMap().containsKey(catAttr))
				schema.getAttributesMap().put(catAttr, catAttr);
		// add matched attributes
		for (String[] couple : match.getMatchedAttributes()) {
			schema.getAttributesMap().put(couple[0], couple[1]);
		}
		// add non matched attributes
		if (!deleteIfNotInCatalog) {
			for (String sourceAttr : invIndexes.getSourceIndex().keySet())
				if (!schema.getAttributesMap().containsKey(sourceAttr))
					schema.getAttributesMap().put(sourceAttr, sourceAttr);
		}
	}

//	public static void main(String[] args) {
		// FileDataConnector fdc = new FileDataConnector();
		// MongoDBConnector mdbc = new MongoDBConnector(fdc);
		// CategoryMatcher cm = new CategoryMatcher(mdbc);
		// Map<String, String> schema = new HashMap<>();
		// schema.put("giorno###prova", "notte###prova");
		// schema.put("lunedi###zappa", "notte###prova");
		// Document spec = new Document().append("bo", "nulla");
		// spec.append("giorno", "1");
		// Document spec2 = new Document().append("lunedi", "cc");
		// spec2.append("terzo", "valore");
		// Document prod = new Document().append("spec", spec);
		// Document prod2 = new Document().append("spec", spec2);
		// prod.append("website", "prova");
		// prod2.append("website", "zappa");
		// cm.updateSpecs(prod, schema);
		// ArrayList<Document> list = new ArrayList<>();
		// list.add(prod);
		// list.add(prod2);
		// System.out.println(cm.mergeProducts(list, schema).get("spec",
		// Document.class));
//	}
}
