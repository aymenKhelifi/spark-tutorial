package org.educationaldatamining.spark.bkt

/**
	* Copyright (C) 2016 Tristan Nixon <tristan.m.nixon@gmail.com>
	*
	* This work is licensed under the Creative Commons Attribution-ShareAlike 4.0 International License.
	* To view a copy of this license, visit http://creativecommons.org/licenses/by-sa/4.0/.
	*
	* This legend must continue to appear in the source code despite modifications or enhancements by any party.
	*/

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.ml.Model
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.StructType
import org.educationaldatamining.spark.ml.hmm.LogProb

/**
	* A Bayesian Knowledge Tracing model
	*
	* Created by Tristan Nixon <tristan.m.nixon@gmail.com> on 6/14/16.
	*/
class BKTModel(override val uid: String)
	extends Model[BKTModel] with BKTParams
{
	/** Setters for BKT parameters **/

	/** @group setParam **/
	def setPInit( value: Double ): BKTModel = set(pInit, value)
	setDefault( pInit -> 0.0 )

	/** @group setParam **/
	def setPLearn( value: Double ): BKTModel = set(pLearn, value)
	setDefault( pLearn -> 0.0 )

	/** @group setParam **/
	def setPGuess( value: Double ): BKTModel = set(pGuess, value)
	setDefault( pGuess -> 0.0 )

	/** @group setParam **/
	def setPSlip( value: Double ): BKTModel = set(pSlip, value)
	setDefault( pSlip -> 0.0 )

	/** Setters for column names **/

	/** @group setParam **/
	def setOppsCol( value: String ): BKTModel = set(oppsCol, value)

	/** @group setParam **/
	def setPCorrectCol( value: String ): BKTModel = set(pCorrectCol, value)

	/** @group setParam **/
	def setPKnownCol( value: String ): BKTModel = set(pKnownCol, value)

	/** PCorrect & PKnown implementations **/

	/**
		* Update the PKnown estimate based on observed behavior
		* @param correct whether or not the observed behavior was correct
		* @param prior the prior estimate of PKnown
		*/
	private[bkt] def updatePKnown(correct: Boolean, prior: Double ): Double =
	{
		// log-transform some values
		val known = new LogProb(prior)
		val unknown = LogProb.ONE - known
		val guessed = new LogProb(getPGuess)
		val slipped = new LogProb(getPSlip)

		// apply learning rate to a posterior PKnown
		def learn( posterior: LogProb ): LogProb = posterior + (LogProb.ONE - posterior) * new LogProb(getPLearn)

		// we update the probability based on observed correctness
		if( correct ){
			// two ways of getting a correct result:
			val pKnewIt = known * (LogProb.ONE - slipped) // prob. student actually knew the answer
			val pGuessedIt = unknown * guessed // prob. student guessed correctly
			learn( pKnewIt / ( pKnewIt +  pGuessedIt ) ).asProb
		} else {
			// two ways of getting an incorrect result:
			val pSlippedUp = known * slipped // student knew the answer, but slipped
			val pDidntKnowIt = unknown * (LogProb.ONE - guessed) // didn't know it, and didn't guess
			learn( pSlippedUp / ( pSlippedUp + pDidntKnowIt ) ).asProb
		}
	}

	/**
		* Calculate the sequence of pKnown probabilities for the observed sequence
		*
		* @param observed
		* @return
		*/
	private[bkt] def pKnown( observed: Array[Boolean] ): Array[Double] =
	{
		// start with our initial probability of knowledge
		val pK = new Array[Double]( observed.length + 1 )
		pK(0) = getPInit
		// update pKnown based o prior value and observed
		for( i <- observed.indices )
			pK(i + 1) = updatePKnown( observed(i), pK(i) )
		pK
	}

	/**
		*
		* @param observed
		* @param pKnown
		* @return
		*/
	private[bkt] def pCorrect( observed: Array[Boolean], pKnown: Array[Double] ): Array[Double] =
	{
		val noSlip = LogProb.ONE - new LogProb(getPSlip)
		val guessed = new LogProb(getPGuess)

		// correct behavior comes from knowing and not slipping
		// or not knowing, but successfully guessing
		def pC( obs: Boolean, pK: LogProb ): Double =
			( ( pK * noSlip ) + ( (LogProb.ONE - pK) * guessed ) ).asProb

		// apply it to all (observation, pKnown) pairs
		observed.zip( pKnown.map( new LogProb(_) ) ).map( ok => pC( ok._1, ok._2) )
	}

	/** implement Model methods **/

	override def copy(extra: ParamMap): BKTModel =
	{
		val newModel = copyValues( new BKTModel(uid), extra )
		newModel.setParent(parent)
	}

	@DeveloperApi
	override def transformSchema(schema: StructType): StructType = validateAndTransformSchema(schema)

	override def transform(dataset: DataFrame): DataFrame =
	{
		transformSchema( dataset.schema, true )
		// throw warning if PCorrect, PKnown not set
		if( $(pCorrectCol).isEmpty || $(pKnownCol).isEmpty )
			logWarning( uid +": BKTModel.transform was called as a NOOP since the pCorrect or pKnown column names are not set!")
		else {
			// run the model on the opportunities
			val pK = udf { (opps: Array[Boolean]) => pKnown(opps) }
			val pC = udf { (opps: Array[Boolean], pK: Array[Double]) => pCorrect(opps,pK) }
			dataset.withColumn( $(pKnownCol), pK( column($(oppsCol)) ) )
			dataset.withColumn( $(pCorrectCol), pC( column($(oppsCol)), column($(pKnownCol)) ) )
		}
		dataset
	}
}