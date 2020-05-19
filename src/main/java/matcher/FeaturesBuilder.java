package matcher;

import java.util.List;
import java.util.Map.Entry;

import model.SourceProductPage;
import model.AbstractProductPage.Specifications;
import models.generator.Configurations;
import models.matcher.BagsOfWordsManager;
import models.matcher.Features;
import models.matcher.Tuple;

/**
 * Build an object {@link Features} provided attributes source comparison and category comparison 
 * @author federico
 *
 */
public class FeaturesBuilder {
	
	private boolean useMi;
	
	public FeaturesBuilder(Configurations conf) {
		this.useMi = conf.isUseMutualInformation();
	}
	
	
	/**
	 * Compute features for classification
	 * @param sList
	 * @param cList
	 * @param a1
	 * @param a2
	 * @param useMI
	 * @return
	 */
	public Features computeFeatures(List<Entry<Specifications, SourceProductPage>> sList,
			List<Entry<Specifications, SourceProductPage>> cList, String a1, String a2, Tuple t) {

		Features features = new Features(t);
		BagsOfWordsManager sBags = new BagsOfWordsManager(a1, a2, sList);
		BagsOfWordsManager cBags = new BagsOfWordsManager(a1, a2, cList);

		features.setSourceJSD(FeatureExtractor.getJSD(sBags));
		features.setCategoryJSD(FeatureExtractor.getJSD(cBags));
		features.setSourceJC(FeatureExtractor.getJC(sBags));
		features.setCategoryJC(FeatureExtractor.getJC(cBags));
		if (this.useMi) {
			features.setSourceMI(FeatureExtractor.getMI(sList, a1, a2));
			features.setCategoryMI(FeatureExtractor.getMI(cList, a1, a2));
		}
		if (features.hasNan())
			throw new ArithmeticException("feature value is NaN");

		return features;
	}
	
	/**
	 * Compute features for training
	 * @param sList coppia di pagine in linkage appartenenti alle sorgenti della tupla
	 * @param cList coppia di pagine in linkage mantenendo come s2 la sorgente della tupla, e cercando tutte le possibili s1 nella categoria
	 * @param a1
	 * @param a2
	 * @param type
	 * @return
	 */
	public Features computeFeatures(List<Entry<Specifications, SourceProductPage>> sList,
			List<Entry<Specifications, SourceProductPage>> cList, String a1, String a2, double type, Tuple t) {

		Features features = computeFeatures(sList, cList, a1, a2, t);
		features.setMatch(type);
		return features;
	}

}
