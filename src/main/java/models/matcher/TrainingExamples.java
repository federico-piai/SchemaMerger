package models.matcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Example of positive and negative pairs of source attributes for training
 * 
 * @author federico
 * 
 */
public class TrainingExamples {
	
	private Set<Tuple> positives;
	private Set<Tuple> negatives;
	
	public TrainingExamples() {
		this.positives = new HashSet<>();
		this.negatives = new HashSet<>();
	}
	
	public void addPositives(Collection<Tuple> tuples) {
		this.positives.addAll(tuples);
	}
	
	public void addNegative(Tuple t) {
		this.negatives.add(t);
	}
	
	/**
	 * Balance positive and negative examples according to ratio, removing exceeding pos or negs 
	 * @param ratioPositives the ratio of positives on total examples
	 */
	public void balancePosNeg(double ratioPositives) {
		//'ratio' is the ratio of positive examples on total examples (p + n = Total; p = ratio * Total)
		//We want now the ratio between pos and neg --> p = r / (1-r) n
		double ratioPosNeg = ratioPositives / (1-ratioPositives);
		if (negatives.size() == 0 || positives.size() == 0){
			throw new RuntimeException("Missing examples for training");
		}
		
		int actualRatioPosNeg = positives.size() / negatives.size();
		if (actualRatioPosNeg < ratioPosNeg) { //need to augment ratio by reducing negatives
			int negSizeDesired = (int) (positives.size() / actualRatioPosNeg);
			this.negatives = sampleSet(negSizeDesired, this.negatives);
		} else if (actualRatioPosNeg > ratioPosNeg) { //need to reduce ratio by reducing positives
			int posSizeDesired = (int) (negatives.size() * actualRatioPosNeg);
			this.positives = sampleSet(posSizeDesired, this.positives);		
		}
	}

	/**
	 * Return a sampled copy of a set
	 * @param sizeDesired
	 * @param currentSet
	 * @return
	 */
	private Set<Tuple> sampleSet(int sizeDesired, Set<Tuple> currentSet) {
		List<Tuple> listTuples = new ArrayList<>(currentSet);
		Collections.shuffle(listTuples);
		return new HashSet<Tuple>(listTuples.subList(0, sizeDesired));
	}
	
	public int posSize() {
		return this.positives.size();
	}
	
	public int negSize() {
		return this.negatives.size();
	}

	public Set<Tuple> getPositives() {
		return positives;
	}

	public Set<Tuple> getNegatives() {
		return negatives;
	}

}
