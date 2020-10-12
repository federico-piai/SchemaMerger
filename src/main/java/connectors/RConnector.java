package connectors;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.rosuda.REngine.REXP;
import org.rosuda.REngine.REXPDouble;
import org.rosuda.REngine.REXPMismatchException;
import org.rosuda.REngine.REngine;
import org.rosuda.REngine.REngineException;
import org.rosuda.REngine.RList;

import models.matcher.DataFrame;
import utils.ExternalAgrawalException;

public class RConnector {

	private REngine eng = null;
	private String modelName;
	private String modelPath;

	public RConnector(String modelPath) {
		Path modelPathObject = Paths.get(modelPath);
		// R creates a variable named after the filename provided. We store it to use it
		// later.
		this.modelName = modelPathObject.getFileName().toString().replaceFirst("[.][^.]+$", "");
		this.modelPath = modelPath;
	}

	public void start() {
		// start REngine
		try {
			/* for debugging */
			//this.eng = REngine.engineForClass("org.rosuda.REngine.JRI.JRIEngine", new String[] { "--vanilla" },
			//		new REngineStdOutput(), false);
			this.eng =
					REngine.engineForClass("org.rosuda.REngine.JRI.JRIEngine");
			// load caret package
			REXP parseAndEval = this.eng.parseAndEval("2+2");
			System.out.println("Testing R engine, result of 2+2: "+parseAndEval.asDouble());
			this.eng.parseAndEval("library(caret)");
			System.out.println("Successfully imported library caret");
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		} catch (REngineException e) {
			e.printStackTrace();
		} catch (REXPMismatchException e) {
			e.printStackTrace();
		}

	}

	public void stop() {
		if (this.eng != null)
			this.eng.close();
	}

	public void loadModel() {
		// load classifier model
		try {
			this.eng.parseAndEval("load('" + System.getProperty("user.dir").replace('\\', '/') + '/' + this.modelPath + "')");
		} catch (REngineException | REXPMismatchException e) {
			e.printStackTrace();
		}
	}

	// generates classifier model
	public void train(String tsPath) throws ExternalAgrawalException {
		try {
			// read training set
			this.eng.parseAndEval("data <- read.csv(\"" + tsPath + "\",header=TRUE)");
			this.eng.parseAndEval("data$Match <- as.factor(data$Match)");
			this.eng.parseAndEval("levels(data$Match) <- c(\"false\", \"true\")");
			this.eng.parseAndEval("data$Match <- factor(data$Match, levels=c(\"true\",\"false\"))");
			// remove unnecessary columns
			this.eng.parseAndEval("dataSub <- subset(data, select=-c(Attribute1, Attribute2, Website1,"
					+ " Website2, Category))");
			// trainControl setup
			this.eng.parseAndEval("tc <- trainControl(method=\"repeatedcv\", number=10, repeats=50, classProbs=TRUE,"
					+ " savePredictions=TRUE, summaryFunction=twoClassSummary)");
			// training: logistic regression with Area under ROC as metric
			this.eng.parseAndEval(modelName + " <- train(Match~., data=dataSub, trControl=tc, method=\"glm\","
					+ " family=binomial(link=\"logit\"), metric=\"ROC\")");
			// save model to file to avoid retraining
			this.eng.parseAndEval("save(" + this.modelName + ", file = \"" + this.modelPath + "\")");

		} catch (REngineException | REXPMismatchException e) {
			throw new ExternalAgrawalException("Problem with R while training", e);
		} catch (Error e) {
			System.out.println("ERROR!!!! " + e.getLocalizedMessage());
		}
	}

	/**
	 * Return probability of match given features between 2 attributes
	 * 
	 * @param df
	 * @return
	 * @throws ExternalAgrawalException 
	 */
	public double[] classify(DataFrame df) throws ExternalAgrawalException {
		double[] predictions = null;
		
		// build dataframe columns
		String[] colNames = { "JSDs", "JSDc", "JCs", "JCc", "MIs", "MIc" };
		double[] colJSDs = df.getJSDs().stream().mapToDouble(Double::doubleValue).toArray();
		double[] colJSDc = df.getJSDc().stream().mapToDouble(Double::doubleValue).toArray();
		double[] colJCs = df.getJCs().stream().mapToDouble(Double::doubleValue).toArray();
		double[] colJCc = df.getJCc().stream().mapToDouble(Double::doubleValue).toArray();
		double[] colMIs = df.getMIs().stream().mapToDouble(Double::doubleValue).toArray();
		double[] colMIc = df.getMIc().stream().mapToDouble(Double::doubleValue).toArray();

		try {
			// create dataframe
			REXP mydf = REXP
					.createDataFrame(new RList(
							new REXP[] { new REXPDouble(colJSDs), new REXPDouble(colJSDc), new REXPDouble(colJCs),
									new REXPDouble(colJCc), new REXPDouble(colMIs), new REXPDouble(colMIc) },
							colNames));
			// pass dataframe to REngine
			this.eng.assign("dataFrame", mydf);
			// predict matches
			this.eng.parseAndEval("predictions <- predict(" + modelName + ", dataFrame, type = 'prob')");
			// System.out.println(eng.parseAndEval("print(predictions$true)"));
			predictions = this.eng.parseAndEval("predictions$true").asDoubles();

		} catch (REXPMismatchException e) {
			dumpSubsetDf(df);
			throw new ExternalAgrawalException("R error", e);
		} catch (REngineException e) {
			dumpSubsetDf(df);
			throw new ExternalAgrawalException("Problem with R engine", e);
		}

		return predictions;
	}

	private void dumpSubsetDf(DataFrame df) {
		System.out.println("dump of subset of R data");
		List<String> csvFormat = df.toCSVFormat();
		List<String> csvFormatSubset = csvFormat.subList(0, Math.min(5, csvFormat.size()));
		System.out.println(String.join("\n", csvFormatSubset));
	}

}
