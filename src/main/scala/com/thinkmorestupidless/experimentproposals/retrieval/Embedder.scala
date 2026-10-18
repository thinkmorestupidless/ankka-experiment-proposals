package com.thinkmorestupidless.experimentproposals.retrieval

import java.nio.file.{Files, Path}

/** Turns text into a vector; the model name and revision are recorded on every chunk (FR-013). */
trait Embedder:
  def model: String
  def revision: String
  def dims: Int
  def embed(text: String): Vector[Float]

object Embedder:
  def normalise(v: Array[Float]): Vector[Float] =
    val norm = math.sqrt(v.map(x => x.toDouble * x).sum).toFloat
    if norm == 0f then v.toVector else v.map(_ / norm).toVector

  def cosine(a: Vector[Float], b: Vector[Float]): Double =
    if a.length != b.length then 0.0
    else
      var dot = 0.0; var na = 0.0; var nb = 0.0
      var i   = 0
      while i < a.length do
        dot += a(i) * b(i); na += a(i) * a(i); nb += b(i) * b(i); i += 1
      if na == 0 || nb == 0 then 0.0 else dot / math.sqrt(na * nb)

  /** `hash` for tests, otherwise the local ONNX model (research.md R5). */
  def forName(name: String, cache: String): Embedder =
    if name == "hash" then HashEmbedder
    else OnnxEmbedder(name, Path.of(cache.replaceFirst("^~", sys.props("user.home"))))

/** Deterministic, dependency-free, 384 dimensions: the hashing trick over word tokens. */
object HashEmbedder extends Embedder:
  val model    = "hash"
  val revision = "1"
  val dims     = 384
  def embed(text: String): Vector[Float] =
    val v = new Array[Float](dims)
    text.toLowerCase.split("[^a-z0-9]+").filter(_.nonEmpty).foreach { w =>
      val h    = w.hashCode
      val idx  = math.floorMod(h, dims)
      val sign = if (h >>> 16) % 2 == 0 then 1f else -1f
      v(idx) += sign
    }
    Embedder.normalise(v)

/**
 * `BAAI/bge-small-en-v1.5` through ONNX Runtime and the Hugging Face tokenizer, loaded once from a
 * local cache that is filled from the Hugging Face hub on first use. CLS pooling, then
 * normalisation, as the model card says. Never used by a test (R20 item 3).
 */
final class OnnxEmbedder(val model: String, cache: Path) extends Embedder:
  import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
  import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtSession}

  val revision = "main"
  val dims     = 384

  private lazy val (env, session, tokenizer) =
    val dir = cache.resolve(model.replace('/', '_'))
    Files.createDirectories(dir)
    val modelFile     = fetch(dir, "onnx/model.onnx", "model.onnx")
    val tokenizerFile = fetch(dir, "tokenizer.json", "tokenizer.json")
    val e             = OrtEnvironment.getEnvironment
    (
      e,
      e.createSession(modelFile.toString, new OrtSession.SessionOptions()),
      HuggingFaceTokenizer.newInstance(tokenizerFile)
    )

  private def fetch(dir: Path, remote: String, local: String): Path =
    val target = dir.resolve(local)
    if !Files.exists(target) then
      val url =
        java.net.URI.create(s"https://huggingface.co/$model/resolve/$revision/$remote").toURL
      val in = url.openStream()
      try Files.copy(in, target)
      finally in.close()
    target

  def embed(text: String): Vector[Float] =
    val encoding = tokenizer.encode(text)
    val ids      = encoding.getIds
    val mask     = encoding.getAttentionMask
    val types    = encoding.getTypeIds
    val shape    = Array(1L, ids.length.toLong)
    val inputs   = new java.util.HashMap[String, OnnxTensor]()
    inputs.put("input_ids", OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(ids), shape))
    inputs.put(
      "attention_mask",
      OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(mask), shape)
    )
    if session.getInputNames.contains("token_type_ids") then
      inputs.put(
        "token_type_ids",
        OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(types), shape)
      )
    val result = session.run(inputs)
    try
      val hidden = result.get(0).getValue.asInstanceOf[Array[Array[Array[Float]]]]
      Embedder.normalise(hidden(0)(0)) // the [CLS] token
    finally
      result.close()
      inputs.values.forEach(_.close())
