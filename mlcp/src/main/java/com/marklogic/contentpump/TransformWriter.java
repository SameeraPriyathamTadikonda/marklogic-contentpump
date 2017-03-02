/*
 * Copyright 2003-2017 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.marklogic.contentpump;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.contentpump.utilities.TransformHelper;
import com.marklogic.io.Base64;
import com.marklogic.mapreduce.ContentType;
import com.marklogic.mapreduce.ContentWriter;
import com.marklogic.mapreduce.DocumentURI;
import com.marklogic.mapreduce.MarkLogicConstants;
import com.marklogic.mapreduce.MarkLogicCounter;
import com.marklogic.mapreduce.ZipEntryInputStream;
import com.marklogic.mapreduce.utilities.AssignmentManager;
import com.marklogic.mapreduce.utilities.InternalUtilities;
import com.marklogic.xcc.AdhocQuery;
import com.marklogic.xcc.ContentCapability;
import com.marklogic.xcc.ContentPermission;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.DocumentRepairLevel;
import com.marklogic.xcc.RequestOptions;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.Session.TransactionMode;
import com.marklogic.xcc.ValueFactory;
import com.marklogic.xcc.exceptions.RequestException;
import com.marklogic.xcc.exceptions.RequestServerException;
import com.marklogic.xcc.exceptions.XQueryException;
import com.marklogic.xcc.types.ValueType;
import com.marklogic.xcc.types.XName;
import com.marklogic.xcc.types.XdmValue;

/**
 * ContentWriter that does server-side transform and insert
 * @author ali
 *
 * @param <VALUEOUT>
 */
public class TransformWriter<VALUEOUT> extends ContentWriter<VALUEOUT> {
    public static final Log LOG = LogFactory.getLog(ContentWriter.class);
    static final long BATCH_MIN_VERSION = 9000030;
    protected String moduleUri;
    protected String functionNs;
    protected String functionName;
    protected String functionParam;
    protected ContentType contentType;
    protected AdhocQuery[] queries;
    protected List<DocumentURI>[] pendingURIs;
    protected XdmValue[][] uris;
    protected XdmValue[][] values;
    protected XdmValue[][] optionsVals;
    protected HashMap<String, String> optionsMap;
    protected XName uriName;
    protected XName contentName;
    protected XName optionsName;
    protected String query;

    public TransformWriter(Configuration conf,
        Map<String, ContentSource> hostSourceMap, boolean fastLoad,
        AssignmentManager am, long effectiveVersion) {
        super(conf, hostSourceMap, fastLoad, am, effectiveVersion);

        batchSize = effectiveVersion >= BATCH_MIN_VERSION ? batchSize : 1;
        moduleUri = conf.get(ConfigConstants.CONF_TRANSFORM_MODULE);
        functionNs = conf.get(ConfigConstants.CONF_TRANSFORM_NAMESPACE, "");
        functionName = conf.get(ConfigConstants.CONF_TRANSFORM_FUNCTION,
            "transform");
        functionParam = conf.get(ConfigConstants.CONF_TRANSFORM_PARAM, "");
        String contentTypeStr = conf.get(MarkLogicConstants.CONTENT_TYPE,
            MarkLogicConstants.DEFAULT_CONTENT_TYPE);
        contentType = ContentType.valueOf(contentTypeStr);
        queries = new AdhocQuery[sessions.length];
        
        pendingURIs = new ArrayList[sessions.length];
        for (int i = 0; i < sessions.length; i++) {
            pendingURIs[i] = new ArrayList<DocumentURI>();
        }
        uris = new XdmValue[counts.length][batchSize];
        values = new XdmValue[counts.length][batchSize];
        optionsVals = new XdmValue[counts.length][batchSize];
        optionsMap = new HashMap<String, String>();
        uriName = new XName("URI");
        contentName = new XName("CONTENT");
        optionsName = new XName("INSERT-OPTIONS");
        query = constructQryString(moduleUri, functionNs,
                functionName, functionParam, effectiveVersion);
        if (LOG.isDebugEnabled()) {
            LOG.debug("query:"+query);
        }
    }
    
    @Override
    protected boolean needCommit() {
        return txnSize > 1;
    }
    
    private static String constructQryString(String moduleUri, 
            String functionNs, String functionName,
            String functionParam, long effectiveVersion) {
        boolean compatibleMode = effectiveVersion < BATCH_MIN_VERSION; 
        StringBuilder q = new StringBuilder();
        q.append("xquery version \"1.0-ml\";\n")
        .append("import module namespace hadoop = \"http://marklogic.com")
        .append("/xdmp/hadoop\" at \"/MarkLogic/hadoop.xqy\";\n")
        .append("declare variable $URI as xs:string* external;\n")
        .append("declare variable $CONTENT as item()* external;\n")
        .append("declare variable $INSERT-OPTIONS as ")
        .append(compatibleMode ? 
            "element() external;\nhadoop:transform-and-insert(\"" :
            "map:map* external;\nhadoop:transform-insert-batch(\"")
        .append(moduleUri)
        .append("\",\"").append(functionNs).append("\",\"")
        .append(functionName).append("\",\"")
        .append(functionParam.replace("\"", "\"\""))
        .append("\", $URI, $CONTENT, $INSERT-OPTIONS)");
        return q.toString();
    }

    @Override
    public void write(DocumentURI key, VALUEOUT value) throws IOException,
        InterruptedException {
        int fId = 0;
        String uri = InternalUtilities.getUriWithOutputDir(key, outputDir);
        if (fastLoad) {
            if (!countBased) {
                // placement for legacy or bucket
                fId = am.getPlacementForestIndex(key);
                sfId = fId;
            } else {
                if (sfId == -1) {
                    sfId = am.getPlacementForestIndex(key);
                }
                fId = sfId;
            }
        }
        int sid = fId;
        addValue(uri, value, sid);
        pendingURIs[sid].add(key);
        if (++counts[sid] == batchSize) {
            if (sessions[sid] == null) {
                sessions[sid] = getSession(sid, false);
                queries[sid] = getAdhocQuery(sid);
            } 
            try {
                queries[sid].setNewVariables(uriName, uris[sid]);
                queries[sid].setNewVariables(contentName, values[sid]);
                queries[sid].setNewVariables(optionsName, optionsVals[sid]);
                sessions[sid].submitRequest(queries[sid]);
                stmtCounts[sid]++;
                if (countBased) {
                    sfId = -1;
                }  
                if (needCommit) {
                    commitUris[sid].addAll(pendingURIs[sid]);
                } else {
                    succeeded += batchSize;
                }
            } catch (RequestServerException e) {
                // log error and continue on RequestServerException
                if (e instanceof XQueryException) {
                    LOG.error(((XQueryException) e).getFormatString());
                } else {
                    LOG.error(e.getMessage());
                }
                LOG.warn("Failed document " + key);
                failed += pendingURIs[sid].size();
                pendingURIs[sid].clear();
            } catch (RequestException e) {
                if (sessions[sid] != null) {
                    sessions[sid].close();
                }
                if (countBased) {
                    rollbackCount(sid);
                }
                throw new IOException(e);
            } finally {
                pendingURIs[sid].clear();
                counts[sid] = 0;
            }
            boolean committed = false;
            if (stmtCounts[sid] == txnSize && needCommit) {
                commit(sid);
                stmtCounts[sid] = 0;
                committed = true;
            }
            if ((!fastLoad) && ((!needCommit) || committed)) { 
                // rotate to next host and reset session
                hostId = (hostId + 1)%forestIds.length;
                sessions[0] = null;
            }
        }
    }
    
    private static String getTypeFromMap(String uri) {
        int idx = uri.lastIndexOf(".");
        Text format = null;
        if (idx != -1) {
            String suff = uri.substring(idx + 1, uri.length());
            if (suff.equalsIgnoreCase("xml"))
                return "xml";
            format = (Text) TransformOutputFormat.mimetypeMap.get(new Text(suff));
        }
        if (format == null) {
            return "binary";
        } else {
            return format.toString();
        }
    }

    private void addValue(String uri, VALUEOUT value, int id) 
            throws UnsupportedEncodingException {
        uris[id][counts[id]] = ValueFactory.newValue(ValueType.XS_STRING,uri);
        ContentType docContentType = contentType;
        if (contentType == ContentType.MIXED) {
            // get type from mimetype map
            docContentType = ContentType.forName(getTypeFromMap(uri));
        }

        switch (docContentType) {
        case BINARY:
            values[id][counts[id]] = 
                ValueFactory.newValue(ValueType.XS_BASE64_BINARY, 
                    Base64.encodeBytes(((BytesWritable) value).getBytes(), 0,
                        ((BytesWritable) value).getLength()));
            optionsMap.put("value-type", 
                    ValueType.XS_BASE64_BINARY.toString());
            break;
                    
        case TEXT:
            if (value instanceof BytesWritable) {
                // in MIXED type, value is byteswritable
                String encoding = options.getEncoding();
                values[id][counts[id]] = 
                    ValueFactory.newValue(ValueType.XS_STRING, 
                        new String(((BytesWritable) value).getBytes(), 0,
                            ((BytesWritable) value).getLength(), encoding));
            } else {
                // must be text or xml
                values[id][counts[id]] = 
                    ValueFactory.newValue(ValueType.XS_STRING, 
                        ((Text) value).toString());
            }
            optionsMap.put("value-type", ValueType.TEXT.toString());
            break;
        case JSON:
        case XML:
            if (value instanceof BytesWritable) {
                // in MIXED type, value is byteswritable
                String encoding = options.getEncoding();
                values[id][counts[id]] = 
                    ValueFactory.newValue(ValueType.XS_STRING, 
                        new String(((BytesWritable) value).getBytes(), 0,
                            ((BytesWritable) value).getLength(), encoding));
            } else if (value instanceof RDFWritable) {
                //RDFWritable's value is Text
                values[id][counts[id]] = 
                    ValueFactory.newValue(ValueType.XS_STRING, 
                        ((RDFWritable)value).getValue().toString());
            } else if (value instanceof ContentWithFileNameWritable) {
                values[id][counts[id]] = 
                    ValueFactory.newValue(ValueType.XS_STRING, 
                    ((ContentWithFileNameWritable)value).getValue().toString());
            }
            else {
                // must be text or xml
                values[id][counts[id]] = 
                    ValueFactory.newValue(ValueType.XS_STRING,
                        ((Text) value).toString());
            }
            optionsMap.put("value-type", ValueType.XS_STRING.toString());
            break;
        case MIXED:
        case UNKNOWN:
            throw new RuntimeException("Unexpected:" + docContentType);
        default:
            throw new UnsupportedOperationException("invalid type:"
                + docContentType);
        }
        String namespace = options.getNamespace();
        if (namespace != null) {
            optionsMap.put("namespace", namespace);
        }
        String lang = options.getLanguage();
        if (lang != null) {
            optionsMap.put("language", "default-language=" + lang);
        }
        ContentPermission[] perms = options.getPermissions();
        StringBuilder rolesReadList = new StringBuilder();
        StringBuilder rolesExeList = new StringBuilder();
        StringBuilder rolesUpdateList = new StringBuilder();
        StringBuilder rolesInsertList = new StringBuilder();
        StringBuilder rolesNodeUpdateList = new StringBuilder();
        if (perms != null && perms.length > 0) {
            for (ContentPermission cp : perms) {
                String roleName = cp.getRole();
                if (roleName == null || roleName.isEmpty()) {
                    LOG.error("Illegal role name: " + roleName);
                    continue;
                }
                ContentCapability cc = cp.getCapability();
                if (cc.equals(ContentCapability.READ)) {
                    if (rolesReadList.length() != 0) {
                        rolesReadList.append(",");
                    }
                    rolesReadList.append(roleName);
                } else if (cc.equals(ContentCapability.EXECUTE)) {
                    if (rolesExeList.length() != 0) {
                        rolesExeList.append(",");
                    }
                    rolesExeList.append(roleName);
                } else if (cc.equals(ContentCapability.INSERT)) {
                    if (rolesInsertList.length() != 0) {
                        rolesInsertList.append(",");
                    }
                    rolesInsertList.append(roleName);
                } else if (cc.equals(ContentCapability.UPDATE)) {
                    if (rolesUpdateList.length() != 0) {
                        rolesUpdateList.append(",");
                    }
                    rolesUpdateList.append(roleName);
                } else if (cc.equals(ContentCapability.NODE_UPDATE)) {
                    if (rolesNodeUpdateList.length() != 0) {
                        rolesNodeUpdateList.append(",");
                    }
                    rolesNodeUpdateList.append(roleName);
                }
            }
        }
        optionsMap.put("roles-read", rolesReadList.toString());
        optionsMap.put("roles-execute", rolesExeList.toString());
        optionsMap.put("roles-update", rolesUpdateList.toString());
        optionsMap.put("roles-insert", rolesInsertList.toString());
        optionsMap.put("roles-node-update", rolesNodeUpdateList.toString());

        String[] collections = options.getCollections();
        StringBuilder sb = new StringBuilder();
        if (collections != null || value instanceof ContentWithFileNameWritable) {
            if (collections != null) {
                for (int i = 0; i < collections.length; i++) {
                    if (i != 0)
                        sb.append(",");
                    sb.append(collections[i].trim());
                }
            } 
                
            if (value instanceof ContentWithFileNameWritable) {
                if(collections != null)
                    sb.append(",");
                sb.append(((ContentWithFileNameWritable) value).getFileName());
            }
            
            optionsMap.put("collections", sb.toString());
        }

        optionsMap.put("quality", String.valueOf(options.getQuality()));
        DocumentRepairLevel repairLevel = options.getRepairLevel();
        if (!DocumentRepairLevel.DEFAULT.equals(repairLevel)) {
            optionsMap.put("xml-repair-level", "repair-" + repairLevel);
        }

        String temporalCollection = options.getTemporalCollection();
        if (temporalCollection != null) {
            optionsMap.put("temporal-collection", temporalCollection);
        }
        if (effectiveVersion < BATCH_MIN_VERSION) {
            String optionElem = TransformHelper.mapToElement(optionsMap);
            optionsVals[id][counts[id]] = 
                    ValueFactory.newValue(ValueType.ELEMENT, optionElem);
        } else {
            ObjectNode optionsNode = TransformHelper.mapToNode(optionsMap);
            optionsVals[id][counts[id]] = 
                    ValueFactory.newValue(ValueType.JS_OBJECT, optionsNode);
        }
        optionsMap.clear();
    }

    protected Session getSession(int fId, boolean nextReplica) {
        TransactionMode mode = TransactionMode.AUTO;
        if (needCommit) {
            mode = TransactionMode.UPDATE;
        }
        return getSession(fId, nextReplica, mode);
    }
    
    protected AdhocQuery getAdhocQuery(int sid) {
        AdhocQuery q = sessions[sid].newAdhocQuery(query);
        RequestOptions rOptions = new RequestOptions();
        rOptions.setDefaultXQueryVersion("1.0-ml");
        q.setOptions(rOptions);
        return q;
    }
    
    @Override
    public void close(TaskAttemptContext context) throws IOException,
    InterruptedException {
        for (int i = 0; i < sessions.length; i++) {
            try {
                if (counts[i] > 0) {
                    if (sessions[i] == null) {
                        sessions[i] = getSession(i,false);
                    }
                    if (queries[i] == null) {
                        queries[i] = getAdhocQuery(i);
                    }
                    XdmValue[] urisLeft = new XdmValue[counts[i]];
                    System.arraycopy(uris[i], 0, urisLeft, 0, counts[i]);
                    queries[i].setNewVariables(uriName, urisLeft);
                    XdmValue[] valuesLeft = new XdmValue[counts[i]];
                    System.arraycopy(values[i], 0, valuesLeft, 0, counts[i]);
                    queries[i].setNewVariables(contentName, valuesLeft);
                    XdmValue[] optionsLeft = new XdmValue[counts[i]];
                    System.arraycopy(optionsVals[i], 0, optionsLeft, 0, counts[i]);
                    queries[i].setNewVariables(optionsName, optionsLeft);
                    sessions[i].submitRequest(queries[i]); 
                    if (!needCommit) {
                        succeeded += counts[i]; 
                    } else {
                        stmtCounts[i]++;
                        commitUris[i].addAll(pendingURIs[i]);
                    }
                }
            } catch (RequestServerException e) {
                // log error and continue on RequestServerException
                LOG.error("Error commiting transaction", e);
                failed += commitUris[i].size();   
                for (DocumentURI failedUri : commitUris[i]) {
                    LOG.warn("Failed document " + failedUri);
                }
                commitUris[i].clear();
            } catch (RequestException e) {
                if (sessions[i] != null) {
                    sessions[i].close();
                }
                if (countBased) {
                    rollbackCount(i);
                }
                failed += commitUris[i].size();
                commitUris[i].clear();
                throw new IOException(e);
            } 
            if (stmtCounts[i] > 0 && needCommit) {  
                commit(i);
                succeeded += commitUris[i].size();
            }
        }
        for (int i = 0; i < sessions.length; i++) {
            if (sessions[i] != null) {
                sessions[i].close();
            }
        }
        if (is != null) {
            is.close();
            if (is instanceof ZipEntryInputStream) {
                ((ZipEntryInputStream)is).closeZipInputStream();
            }
        }
        Counter committedCounter = context.getCounter(
                MarkLogicCounter.OUTPUT_RECORDS_COMMITTED);
        synchronized(committedCounter) {
            committedCounter.increment(succeeded);
        }
        Counter failedCounter = context.getCounter(
                MarkLogicCounter.OUTPUT_RECORDS_FAILED);
        synchronized(failedCounter) {
            failedCounter.increment(failed);
        }
    }
}