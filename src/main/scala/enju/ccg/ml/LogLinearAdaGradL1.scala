package enju.ccg.ml

class LogLinearAdaGradL1[L](override val weights: NumericBuffer[Float], val lambda: Float, val eta: Float)
    extends OnlineLogLinearTrainer[L] {
  val lastUpdates = new NumericBuffer[Int](weights.size)
  val diagGt = new NumericBuffer[Float](weights.size)

  override protected def weight(idx: Int): Float =
    if (lastUpdates(idx) == time) weights(idx)
    else {
      val currentXti = weights(idx)
      if (currentXti == 0.0F) 0.0F
      else {
        val t0 = lastUpdates(idx)
        assert(time != 0)
        val ht0ii = 1.0 + Math.sqrt(diagGt(idx))
        val newWeight = Math.signum(currentXti) * Math.max(
          0.0, Math.abs(currentXti) - (lambda * eta / ht0ii) * (time - t0))
        weights(idx) = newWeight.toFloat
        lastUpdates(idx) = time
        newWeight.toFloat
      }
    }

  override def updateExampleWeights(e: Example[L], gold: L, derivative: Float): Unit = {
    // Here, we negate the gradient. This is because original formulation by Duch et al.
    // minimizes the objective, while we maximize the objective.
    val gti = -derivative
    val deltaDiagGti = gti * gti // these are shared by all i below, so we cache here

    val feats = e.featVec
    var j = 0
    while (j < feats.size) {
      val i = feats(j)

      //val xti = weight(i) // This automatically perform lazy update of the target weight
      val xti = weights(i) // weighs(i) must be lazy-updated at calculating label scores, so we can skip
      diagGt(i) += deltaDiagGti
      val htii = 1.0 + Math.sqrt(diagGt(i))
      val etaOverHtii = eta / htii
      val tempXti = xti - etaOverHtii * gti

      weights(i) = (Math.signum(tempXti) * Math.max(0.0, Math.abs(tempXti) - lambda * etaOverHtii)).toFloat
      lastUpdates(i) = time + 1

      j += 1
    }
  }
  override def postProcess: Unit = {
    (0 until weights.size).foreach { weight(_) }
  }
}
