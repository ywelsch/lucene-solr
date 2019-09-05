/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.lucene.util.IOUtils;
import org.apache.solr.api.Api;
import org.apache.solr.api.V2HttpCall;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CollectionAdminParams;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.solr.util.SimplePostTool;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.common.MapWriter.EMPTY;
import static org.apache.solr.common.SolrException.ErrorCode.BAD_REQUEST;
import static org.apache.solr.common.SolrException.ErrorCode.SERVER_ERROR;
import static org.apache.solr.common.SolrException.ErrorCode.SERVICE_UNAVAILABLE;
import static org.apache.solr.common.cloud.ZkStateReader.BASE_URL_PROP;
import static org.apache.solr.core.RuntimeLib.SHA256;
import static org.apache.solr.handler.ReplicationHandler.FILE_STREAM;

/**
 * The purpose of this class is to store the Jars loaded in memory and to keep only one copy of the Jar in a single node.
 */
public class BlobRepository {
  private static final long MAX_JAR_SIZE = Long.parseLong(System.getProperty("runtime.lib.size", String.valueOf(5 * 1024 * 1024)));
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  static final Random RANDOM;
  static final Pattern BLOB_KEY_PATTERN_CHECKER = Pattern.compile(".*/\\d+");

  static {
    // We try to make things reproducible in the context of our tests by initializing the random instance
    // based on the current seed
    String seed = System.getProperty("tests.seed");
    if (seed == null) {
      RANDOM = new Random();
    } else {
      RANDOM = new Random(seed.hashCode());
    }
  }



  public ByteBuffer getBlob(String sha256) throws IOException {
    ByteBuffer result = tmpBlobs.get(sha256);
    if (result != null) return result;
    result = getFromLocalFs(sha256);
    if (result == null) {
      result = fetchFromOtherNodes(sha256);
    }
    return result;
  }

  private final CoreContainer coreContainer;
  private Map<String, BlobContent> blobs = createMap();
  private Map<String, ByteBuffer> tmpBlobs = new ConcurrentHashMap<>();

  // for unit tests to override
  ConcurrentHashMap<String, BlobContent> createMap() {
    return new ConcurrentHashMap<>();
  }

  public BlobRepository(CoreContainer coreContainer) {
    this.coreContainer = coreContainer;
  }

  public MapWriter fileList(SolrParams params) {
    String sha256 = params.get(SHA256);
    File dir = getBlobsPath().toFile();

    String fromNode = params.get("fromNode");
    if (sha256 != null && fromNode != null) {
      //asking to fetch it from somewhere if it does not exist locally
      if (!new File(dir, sha256).exists()) {
        if ("*".equals(fromNode)) {
          //asking to fetch from a random node
          fetchFromOtherNodes(sha256);
          return EMPTY;
        } else { // asking to fetch from a specific node
          fetchBlobFromNodeAndPersist(sha256, fromNode);
          return MapWriter.EMPTY;
        }
      }
    }
    return ew -> dir.listFiles((f, name) -> {
      if (sha256 == null || name.equals(sha256)) {
        ew.putNoEx(name, (MapWriter) ew1 -> {
          File file = new File(f, name);
          ew1.put("size", file.length());
          ew1.put("timestamp", file.lastModified());
        });
      }
      return false;
    });
  }

  /**
   * Fetch a blob from a given node and persist it to local disk
   * and memory
   */

  private ByteBuffer fetchBlobFromNodeAndPersist(String sha256, String fromNode) {
    log.info("fetching a blob {} from {} ", sha256, fromNode);
    ByteBuffer[] result = new ByteBuffer[1];
    String url = coreContainer.getZkController().getZkStateReader().getBaseUrlForNodeName(fromNode);
    if (url == null) throw new SolrException(BAD_REQUEST, "No such node");
    coreContainer.getUpdateShardHandler().getUpdateExecutor().submit(() -> {
      String fromUrl = url.replace("/solr", "/api") + "/node/blob/" + sha256;
      try {
        HttpClient httpClient = coreContainer.getUpdateShardHandler().getDefaultHttpClient();
        result[0] = Utils.executeGET(httpClient, fromUrl, Utils.newBytesConsumer((int) MAX_JAR_SIZE));
        String actualSha256 = sha256Digest(result[0]);
        if (sha256.equals(actualSha256)) {
          persistToFile(result[0], sha256);
        } else {
          result[0] = null;
          log.error("expected sha256 : {} actual sha256: {} from blob downloaded from {} ", sha256, actualSha256, fromNode);
        }
      } catch (IOException e) {
        log.error("Unable to fetch jar: {} from node: {}", sha256, fromNode);
      }
    });
    return result[0];
  }

  public Path getBlobsPath() {
    return SolrResourceLoader.getBlobsDirPath(this.coreContainer.getResourceLoader().getInstancePath());
  }

  // I wanted to {@link SolrCore#loadDecodeAndCacheBlob(String, Decoder)} below but precommit complains

  /**
   * Returns the contents of a blob containing a ByteBuffer and increments a reference count. Please return the
   * same object to decrease the refcount. This is normally used for storing jar files, and binary raw data.
   * If you are caching Java Objects you want to use {@code SolrCore#loadDecodeAndCacheBlob(String, Decoder)}
   *
   * @param key it is a combination of blobname and version like blobName/version
   * @return The reference of a blob
   */
  public BlobContentRef<ByteBuffer> getBlobIncRef(String key) {
    return getBlobIncRef(key, () -> addBlob(key));
  }

  /**
   * Internal method that returns the contents of a blob and increments a reference count. Please return the same
   * object to decrease the refcount. Only the decoded content will be cached when this method is used. Component
   * authors attempting to share objects across cores should use
   * {@code SolrCore#loadDecodeAndCacheBlob(String, Decoder)} which ensures that a proper close hook is also created.
   *
   * @param key     it is a combination of blob name and version like blobName/version
   * @param decoder a decoder that knows how to interpret the bytes from the blob
   * @return The reference of a blob
   */
  BlobContentRef<Object> getBlobIncRef(String key, Decoder<Object> decoder) {
    return getBlobIncRef(key.concat(decoder.getName()), () -> addBlob(key, decoder));
  }

  BlobContentRef getBlobIncRef(String key, Decoder decoder, String url, String sha256) {
    StringBuffer keyBuilder = new StringBuffer(key);
    if (decoder != null) keyBuilder.append(decoder.getName());
    keyBuilder.append("/").append(sha256);

    return getBlobIncRef(keyBuilder.toString(), () -> new BlobContent<>(key, fetchBlobAndVerify(key, url, sha256), decoder));
  }

  // do the actual work returning the appropriate type...
  private <T> BlobContentRef<T> getBlobIncRef(String key, Callable<BlobContent<T>> blobCreator) {
    BlobContent<T> aBlob;
    if (this.coreContainer.isZooKeeperAware()) {
      synchronized (blobs) {
        aBlob = blobs.get(key);
        if (aBlob == null) {
          try {
            aBlob = blobCreator.call();
          } catch (Exception e) {
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Blob loading failed: " + e.getMessage(), e);
          }
        }
      }
    } else {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Blob loading is not supported in non-cloud mode");
      // todo
    }
    BlobContentRef<T> ref = new BlobContentRef<>(aBlob);
    synchronized (aBlob.references) {
      aBlob.references.add(ref);
    }
    return ref;
  }

  // For use cases sharing raw bytes
  private BlobContent<ByteBuffer> addBlob(String key) {
    ByteBuffer b = fetchBlob(key);
    BlobContent<ByteBuffer> aBlob = new BlobContent<>(key, b);
    blobs.put(key, aBlob);
    return aBlob;
  }

  // for use cases sharing java objects
  private BlobContent<Object> addBlob(String key, Decoder<Object> decoder) {
    ByteBuffer b = fetchBlob(key);
    String keyPlusName = key + decoder.getName();
    BlobContent<Object> aBlob = new BlobContent<>(keyPlusName, b, decoder);
    blobs.put(keyPlusName, aBlob);
    return aBlob;
  }

  static String INVALID_JAR_MSG = "Invalid jar from  , expected sha256 hash : {0} , actual : {1}";

  private ByteBuffer fetchBlobAndVerify(String key, String url, String sha256) throws IOException {
    ByteBuffer byteBuffer = null;
    if (sha256 != null) {
      byteBuffer = getFromLocalFs(sha256);
    }
    if (byteBuffer == null) byteBuffer = getAndValidate(key, url, sha256);
    return byteBuffer;
  }

  private ByteBuffer fetchFromOtherNodes(String sha256) {
    ByteBuffer[] result = new ByteBuffer[1];
    ArrayList<String> l = shuffledNodes();
    ModifiableSolrParams solrParams = new ModifiableSolrParams();
    solrParams.add(SHA256, sha256);
    ZkStateReader stateReader = coreContainer.getZkController().getZkStateReader();
    for (String liveNode : l) {
      try {
        String baseurl = stateReader.getBaseUrlForNodeName(liveNode);
        String url = baseurl.replace("/solr", "/api");
        String reqUrl = url + "/node/blob?wt=javabin&omitHeader=true&sha256=" + sha256;
        boolean nodeHasBlob = false;
        Object nl = Utils.executeGET(coreContainer.getUpdateShardHandler().getDefaultHttpClient(), reqUrl, Utils.JAVABINCONSUMER);
        if (Utils.getObjectByPath(nl, false, Arrays.asList("blob", sha256)) != null) {
          nodeHasBlob = true;
        }

        if (nodeHasBlob) {
          result[0] = fetchBlobFromNodeAndPersist(sha256, liveNode);
          if (result[0] != null) break;
        }
      } catch (Exception e) {
        //it's OK for some nodes to fail
      }
    }

    return result[0];
  }

  /**
   * get a list of nodes randomly shuffled
   * * @lucene.internal
   */
  public ArrayList<String> shuffledNodes() {
    Set<String> liveNodes = coreContainer.getZkController().getZkStateReader().getClusterState().getLiveNodes();
    ArrayList<String> l = new ArrayList(liveNodes);
    Collections.shuffle(l, RANDOM);
    return l;
  }

  private ByteBuffer getAndValidate(String key, String url, String sha256) throws IOException {
    ByteBuffer byteBuffer = fetchFromUrl(key, url);
    String computedDigest = sha256Digest(byteBuffer);
    if (!computedDigest.equals(sha256)) {
      throw new SolrException(SERVER_ERROR, StrUtils.formatString(INVALID_JAR_MSG, sha256, computedDigest));
    }
    File file = new File(getBlobsPath().toFile(), sha256);
    try (FileOutputStream fos = new FileOutputStream(file)) {
      fos.write(byteBuffer.array(), byteBuffer.arrayOffset(), byteBuffer.limit());
      IOUtils.fsync(file.toPath(), false);
    }
    return byteBuffer;
  }

  /**
   * internal API
   */
  public void persistToFile(ByteBuffer b, String sha256) throws IOException {
    String actual = sha256Digest(b);
    if(!Objects.equals(actual, sha256)){
      throw new SolrException(SERVER_ERROR, "invalid sha256 for blob actual: "+ actual+" expected : "+ sha256);
    }
    File file = new File(getBlobsPath().toFile(), sha256);
    try (FileOutputStream fos = new FileOutputStream(file)) {
      fos.write(b.array(), 0, b.limit());
    }
    log.info("persisted a blob {} ", sha256);
    IOUtils.fsync(file.toPath(), false);
  }


  private ByteBuffer getFromLocalFs(String sha256) throws IOException {
    Path p = getBlobsPath();
    File f = new File(p.toFile(), sha256);
    if (!f.exists()) return null;
    byte[] b = new byte[(int) f.length()];
    try (FileInputStream fis = new FileInputStream(f)) {
      fis.read(b);
      ByteBuffer byteBuffer = ByteBuffer.wrap(b);
      if (sha256.equals(sha256Digest(byteBuffer))) {
        return byteBuffer;
      } else {
        return null;

      }
    }
  }

  public static String sha256Digest(ByteBuffer buf) {
    try {
      return DigestUtils.sha256Hex(new ByteBufferInputStream(ByteBuffer.wrap(buf.array(), buf.arrayOffset(), buf.limit())));
    } catch (IOException e) {
      throw new RuntimeException("Unable to compute sha256", e);
    }
  }


  /**
   * Package local for unit tests only please do not use elsewhere
   */
  ByteBuffer fetchBlob(String key) {
    Replica replica = getSystemCollReplica();
    String url = replica.getStr(BASE_URL_PROP) + "/" + CollectionAdminParams.SYSTEM_COLL + "/blob/" + key + "?wt=filestream";
    return fetchFromUrl(key, url);
  }

  ByteBuffer fetchFromUrl(String key, String url) {
    HttpClient httpClient = coreContainer.getUpdateShardHandler().getDefaultHttpClient();
    HttpGet httpGet = new HttpGet(url);
    ByteBuffer b;
    HttpResponse response = null;
    HttpEntity entity = null;
    try {
      response = httpClient.execute(httpGet);
      entity = response.getEntity();
      int statusCode = response.getStatusLine().getStatusCode();
      if (statusCode != 200) {
        throw new SolrException(SolrException.ErrorCode.NOT_FOUND, "no such resource available: " + key + ", url : " + url);
      }

      try (InputStream is = entity.getContent()) {
        b = SimplePostTool.inputStreamToByteArray(is, MAX_JAR_SIZE);
      }
    } catch (Exception e) {
      log.error("Error loading resource " + url, e);
      if (e instanceof SolrException) {
        throw (SolrException) e;
      } else {
        throw new SolrException(SolrException.ErrorCode.NOT_FOUND, "could not load : " + key, e);
      }
    } finally {
      Utils.consumeFully(entity);
    }
    return b;
  }

  private Replica getSystemCollReplica() {
    ZkStateReader zkStateReader = this.coreContainer.getZkController().getZkStateReader();
    ClusterState cs = zkStateReader.getClusterState();
    DocCollection coll = cs.getCollectionOrNull(CollectionAdminParams.SYSTEM_COLL);
    if (coll == null)
      throw new SolrException(SERVICE_UNAVAILABLE, CollectionAdminParams.SYSTEM_COLL + " collection not available");
    ArrayList<Slice> slices = new ArrayList<>(coll.getActiveSlices());
    if (slices.isEmpty())
      throw new SolrException(SERVICE_UNAVAILABLE, "No active slices for " + CollectionAdminParams.SYSTEM_COLL + " collection");
    Collections.shuffle(slices, RANDOM); //do load balancing

    Replica replica = null;
    for (Slice slice : slices) {
      List<Replica> replicas = new ArrayList<>(slice.getReplicasMap().values());
      Collections.shuffle(replicas, RANDOM);
      for (Replica r : replicas) {
        if (r.getState() == Replica.State.ACTIVE) {
          if (zkStateReader.getClusterState().getLiveNodes().contains(r.get(ZkStateReader.NODE_NAME_PROP))) {
            replica = r;
            break;
          } else {
            log.info("replica {} says it is active but not a member of live nodes", r.get(ZkStateReader.NODE_NAME_PROP));
          }
        }
      }
    }
    if (replica == null) {
      throw new SolrException(SERVICE_UNAVAILABLE, "No active replica available for " + CollectionAdminParams.SYSTEM_COLL + " collection");
    }
    return replica;
  }

  /**
   * This is to decrement a ref count
   *
   * @param ref The reference that is already there. Doing multiple calls with same ref will not matter
   */
  public void decrementBlobRefCount(BlobContentRef ref) {
    if (ref == null) return;
    synchronized (ref.blob.references) {
      if (!ref.blob.references.remove(ref)) {
        log.error("Multiple releases for the same reference");
      }
      if (ref.blob.references.isEmpty()) {
        blobs.remove(ref.blob.key);
      }
    }
  }

  BlobRead blobRead = new BlobRead();

  public void putTmpBlob(ByteBuffer buf, String sha256) {
    tmpBlobs.put(sha256, buf);
  }

  public void removeTmpBlob(String sha256) {
    tmpBlobs.remove(sha256);
  }


  class BlobRead extends RequestHandlerBase implements PermissionNameProvider {

    @Override
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) {

    }

    @Override
    public String getDescription() {
      return "List fetch blobs";
    }

    @Override
    public Name getPermissionName(AuthorizationContext request) {
      return Name.BLOB_READ;
    }

    @Override
    public Collection<Api> getApis() {
      return Collections.singleton(new Api(Utils.getSpec("node.blob.GET")) {
        @Override
        public void call(SolrQueryRequest req, SolrQueryResponse rsp) {
          String sha256 = ((V2HttpCall) req.getHttpSolrCall()).getUrlParts().get("sha256");
          if (sha256 == null) {
            rsp.add("blob", fileList(req.getParams()));
          } else {
            try {
              ByteBuffer buf = getBlob(sha256);
              if (buf == null) {
                throw new SolrException(SolrException.ErrorCode.NOT_FOUND, "No such blob");
              } else {
                ByteBuffer copyBuf = buf;
                ModifiableSolrParams solrParams = new ModifiableSolrParams();
                solrParams.add(CommonParams.WT, FILE_STREAM);
                req.setParams(SolrParams.wrapDefaults(solrParams, req.getParams()));
                rsp.add(FILE_STREAM, (SolrCore.RawWriter) os -> os.write(copyBuf.array(), copyBuf.arrayOffset(), copyBuf.limit()));
              }

            } catch (IOException e) {
              throw new SolrException(SERVER_ERROR, e);
            }
          }
        }
      });
    }

    @Override
    public Boolean registerV1() {
      return Boolean.FALSE;
    }

    @Override
    public Boolean registerV2() {
      return Boolean.TRUE;
    }
  }


  public static class BlobContent<T> {
    public final String key;
    private final T content; // holds byte buffer or cached object, holding both is a waste of memory
    // ref counting mechanism
    private final Set<BlobContentRef> references = new HashSet<>();

    public BlobContent(String key, ByteBuffer buffer, Decoder<T> decoder) {
      this.key = key;
      this.content = decoder == null ? (T) buffer : decoder.decode(new ByteBufferInputStream(buffer));
    }

    @SuppressWarnings("unchecked")
    public BlobContent(String key, ByteBuffer buffer) {
      this.key = key;
      this.content = (T) buffer;
    }

    /**
     * Get the cached object.
     *
     * @return the object representing the content that is cached.
     */
    public T get() {
      return this.content;
    }

  }

  public interface Decoder<T> {

    /**
     * A name by which to distinguish this decoding. This only needs to be implemented if you want to support
     * decoding the same blob content with more than one decoder.
     *
     * @return The name of the decoding, defaults to empty string.
     */
    default String getName() {
      return "";
    }

    /**
     * A routine that knows how to convert the stream of bytes from the blob into a Java object.
     *
     * @param inputStream the bytes from a blob
     * @return A Java object of the specified type.
     */
    T decode(InputStream inputStream);
  }


  public static class BlobContentRef<T> {
    public final BlobContent<T> blob;

    public BlobContentRef(BlobContent<T> blob) {
      this.blob = blob;
    }
  }
}
