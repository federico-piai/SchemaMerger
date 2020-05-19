package matcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import connectors.dao.AlignmentDao;
import model.AbstractProductPage.Specifications;
import model.Source;
import model.SourceProductPage;
import models.generator.Configurations;
import models.matcher.Features;
import models.matcher.TrainingExamples;
import models.matcher.Tuple;

/**
 * Generator of training sets, ie sets of pairs of attributes, with features
 * computed AND match/mismatch
 * 
 * @author marco
 * @see Features
 *
 */
public class TrainingSetGenerator {

	private AlignmentDao dao;
	private Map<String, List<String>> clonedSources;
	private FeaturesBuilder fb;
	
	public TrainingSetGenerator(FeaturesBuilder fb, AlignmentDao dao, Map<String, 
			List<String>> clSources) {
		this.dao = dao;
		this.clonedSources = clSources;
		this.fb = fb;
	}	
	
	public TrainingSetGenerator(AlignmentDao dao, Map<String, List<String>> clSources, Configurations conf) {
		this(new FeaturesBuilder(conf), dao, clSources);
	}

	/**
	 * @param sampleSize number of original pages, from which we will look for linked pages and compare them 
	 * @param setSize expected number of examples ({@link Tuple}), i.e. pairs of neg and pos atts
	 * @param useWebsite
	 * @param addTuples add the information on the tuple to the row containing the features
	 * @param ratio
	 * @param category
	 * @return
	 */
	public List<String> getTrainingSetWithTuples(int sampleSize, int setSize, boolean addTuples,
			double ratio, String category, boolean useMI, String fixedCatalogSource) {

		TrainingExamples finalExamples = null;
		boolean hasEnoughExamples = false;
		int newSizeP, newSizeN, sizeP = 0, sizeN = 0, tentatives = 0;

		/*
		 *  We loop until we have a reasonable number of examples, this number can variate depending on the number of attributes and linked pages
		 *  of the randomly picked pages. 
		 *  Note that we keep anyway the biggest set of examples found until now, so that if we make too many tentatives, we eventually use this biggest
		 *  set as the good one even if it is not big enough 
		 */
		do {
			List<SourceProductPage> sample = this.dao.getSamplePagesFromCategory(sampleSize, category,  
					fixedCatalogSource);
			TrainingExamples newExamples = getExamples(sample, ratio, fixedCatalogSource);
			newSizeP = newExamples.posSize();
			newSizeN = newExamples.negSize();
			System.out.println(newSizeP + " + " + newSizeN + " = " + (newSizeP + newSizeN));
			hasEnoughExamples = ((setSize * 0.95) <= (newSizeP + newSizeN));
//					&& ((newSizeP + newSizeN) <= (setSize * 1.05));
			tentatives++;
			if ((sizeP + sizeN) < (newSizeP + newSizeN)) {
				finalExamples = newExamples;
				sizeP = newSizeP;
				sizeN = newSizeN;
			}
		} while (!hasEnoughExamples && tentatives < 4); // there must be enough examples, however don't loop too many
															// times

		// if not enough examples were found, return an empty list
		if (finalExamples == null) {
			System.err.println("NON ABBASTANZA ESEMPI");
			return new ArrayList<String>();
		}

		System.out.println(finalExamples.posSize() + " esempi positivi\t" 
				+ finalExamples.negSize() +" esempi negativi");

		List<Features> trainingSet = computeFeaturesOnTrainingSet(finalExamples, category);

		// Training set is computed. Now, adapt it to the format required (a ~csv used by R).
		List<String> tsRows = new ArrayList<>();
		
		for (Features f: trainingSet) {
			String row = "";
			if (addTuples) {
				row  = f.getT().toRowString();
			}
			row += f.toString() + "," + f.getMatch();
			tsRows.add(row);
		}
		return tsRows;
	}

	/**
	 * From example pages, find all pages in linkage, then generate pairs of
	 * attributes for training set. Tries to respect pos-neg proportion (ratio) : if
	 * it is not respected, try again (max 10 tentatives)
	 * 
	 * @param sample
	 * @param ratio
	 *            pos-neg proportion
	 * @return
	 */
	private TrainingExamples getExamples(List<SourceProductPage> sample, double ratio, String fixedCatalogSource) {
		TrainingExamples examples = new TrainingExamples();

		for (SourceProductPage doc1 : sample) {
			for (String url : doc1.getLinkage()) {
				SourceProductPage doc2 = this.dao.getPageFromUrlIfExistsInDataset(url);

				if (doc2 != null) {

					// check if the two pages belong to cloned sources
					Source source1 = doc1.getSource();
					Source source2 = doc2.getSource();
					if ((!this.clonedSources.containsKey(source1.toString())
							|| !this.clonedSources.get(source1.toString()).contains(source2.toString()))
							&& !source1.getWebsite().equals(source2.getWebsite())) {
						Set<String> schemaIntersection = new HashSet<>(doc1.getSpecifications().keySet());
						schemaIntersection.retainAll(doc2.getSpecifications().keySet());

						// generates positive examples
						List<Tuple> allTmpPosEx = new ArrayList<>();
						schemaIntersection.stream().forEach(a -> {
							Tuple t = new Tuple(a, a, source1.getWebsite(), source2.getWebsite(),
									source1.getCategory());
							allTmpPosEx.add(t);
						});
						Collections.shuffle(allTmpPosEx);
						// get max 10 examples from the same couple (to avoid biases from very similar pages with a lot of attributes)
						List<Tuple> tmpPosEx = allTmpPosEx.subList(0, Math.min(10, allTmpPosEx.size()));
						examples.addPositives(tmpPosEx);

						// generates negative examples
						for (int i = 0; i < tmpPosEx.size() - 1; i++) {
							for (int j = i + 1; j < tmpPosEx.size(); j++) {
								Tuple t1 = tmpPosEx.get(i);
								Tuple t2 = tmpPosEx.get(j);
								examples.addNegative(t1.getMixedTuple(t2));
							}
						}
					}
				}
			}
		}
		examples.balancePosNeg(ratio);
		return examples;
	}

	public List<Features> computeFeaturesOnTrainingSet(TrainingExamples examples, String category) {
		List<Features> examplesFeatures = new ArrayList<>();
		System.out.println("Positive examples");
		examplesFeatures.addAll(getAllFeatures(examples.getPositives(), category, 1));
		System.out.println("Negative examples");
		examplesFeatures.addAll(getAllFeatures(examples.getNegatives(), category, 0));

		return examplesFeatures;
	}

	/**
	 * Retrieve all features for tuples
	 * <p>
	 * For a given tuple, needs to retrieve pairs OF PROVIDED CATEGORY, of those 2
	 * types:
	 * <ul>
	 * <li>[w1, contains(a1)] --[linked with]--> [w2, contains(a2)]
	 * <li>[not(w1), contains(a1)] --[linked with]--> [w2, contains(a2)]
	 * </ul>
	 * Here we try to do it efficiently, limiting the number of call to mongo
	 * 
	 * @param tuples
	 * @param candidateType
	 * @return
	 */
	private List<Features> getAllFeatures(Set<Tuple> tuples, String category, double candidateType) {
		List<Features> features = new ArrayList<>();
		Map<String, Map<String, Map<String, List<Tuple>>>> a1_s2_a2_tuple = tuples.stream()
				.collect(Collectors.groupingBy(Tuple::getAttribute1,
						Collectors.groupingBy(Tuple::getWebsite2, Collectors.groupingBy(Tuple::getAttribute2))));

		// TODO ProgressBar does not work currently (says cannot create system terminal), TODO investigate and fix 
		int counter = 0;
		for (Entry<String, Map<String, Map<String, List<Tuple>>>> a1_s2_a2_tuple_entry : a1_s2_a2_tuple.entrySet()) {
			String attribute1 = a1_s2_a2_tuple_entry.getKey();
			Map<String, Map<String, List<Tuple>>> s2_a2_tuple = a1_s2_a2_tuple_entry.getValue();
			for (Entry<String, Map<String, List<Tuple>>> s2_a2_tuple_entry : s2_a2_tuple.entrySet()) {
				String source2 = s2_a2_tuple_entry.getKey();
				List<SourceProductPage> pagesFromAllSourcesInLinkageS2 = this.dao.getPagesLinkedWithSource2filtered(category, source2,
						attribute1);
				Map<String, List<SourceProductPage>> w1_pagesLinkageS2 = pagesFromAllSourcesInLinkageS2.stream()
						.collect(Collectors.groupingBy(prodPage -> prodPage.getSource().getWebsite(), limitingList(2000)));
				pagesFromAllSourcesInLinkageS2 = pagesFromAllSourcesInLinkageS2.stream().limit(2000).collect(Collectors.toList());
				for (Entry<String, List<Tuple>> a2_tuple : s2_a2_tuple_entry.getValue().entrySet()) {
					getFeatures(candidateType, features, attribute1,
							source2, pagesFromAllSourcesInLinkageS2, w1_pagesLinkageS2, a2_tuple.getKey(), a2_tuple.getValue());
				}
			}
			System.out.println("Done " + ++counter / (double) a1_s2_a2_tuple.size() * 100 +"%");
		}
		return features;
	}
	
	/**
	 * Limit group by 
	 * https://stackoverflow.com/questions/33853611/limit-groupby-in-java-8?utm_medium=organic&utm_source=google_rich_qa&utm_campaign=google_rich_qa
	 * */
	private static <T> Collector<T, ?, List<T>> limitingList(int limit) {
	    return Collector.of(
	                ArrayList::new, 
	                (l, e) -> { if (l.size() < limit) l.add(e); }, 
	                (l1, l2) -> {
	                    l1.addAll(l2.subList(0, Math.min(l2.size(), Math.max(0, limit - l1.size()))));
	                    return l1;
	                }
	           );
	}

	private void getFeatures(double candidateType, List<Features> features, 
			String attribute1, String website2, List<SourceProductPage> pagesInLinkageS2,
			Map<String, List<SourceProductPage>> w1_pagesLinkageS2, String attribute2, List<Tuple> tuplesFromS2withA1A2) {
		// Here we deal with tuple for a specific a1, a2 and w2, and all possible W1s.
		List<Entry<Specifications, SourceProductPage>> cList2 = this.dao.getPairsOfPagesInLinkage(pagesInLinkageS2, website2,
				attribute2);
		List<Tuple> distinctTuples = tuplesFromS2withA1A2.stream().distinct()
				.collect(Collectors.toList());
		for (Tuple t : distinctTuples) {
			// pb.step();
			List<SourceProductPage> subProds_of_w1 = w1_pagesLinkageS2.getOrDefault(t.getWebsite1(), new ArrayList<>());
			List<Entry<Specifications, SourceProductPage>> sList2 = this.dao.getPairsOfPagesInLinkage(subProds_of_w1, website2,
					attribute2);
			
			try {
				Features feature = this.fb.computeFeatures(sList2, cList2,
						attribute1, attribute2, candidateType, t);
				features.add(feature);
			} catch (Exception e) {
				System.err.printf("There was a problem computing features from %s-%s to %s-%s, skipping (error: %s)...", 
						t.getWebsite1(), attribute1,
						website2, attribute2, e.getMessage());
			}
		}
	}

}
