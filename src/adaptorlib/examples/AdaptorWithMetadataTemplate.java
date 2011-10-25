// Copyright 2011 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package adaptorlib.examples;

import adaptorlib.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Demonstrates what code is necessary for putting restricted
 * content onto a GSA.  The key operations are:
 * <ol><li> providing document ids with ACLs
 *   <li> providing document bytes given a document id</ol>
 */
public class AdaptorWithMetadataTemplate extends AbstractAdaptor {
  private static final Logger log
      = Logger.getLogger(AdaptorWithMetadataTemplate.class.getName());
  private Charset encoding = Charset.forName("UTF-8");

  /** Gives list of document ids that you'd like on the GSA. */
  @Override
  public void getDocIds(DocIdPusher pusher) throws InterruptedException {
    // Going to put our doc ids with metadata into a list.
    ArrayList<DocInfo> mockDocInfos = new ArrayList<DocInfo>();

    try {
      // Make set to accumulate meta items.
      Set<MetaItem> metaItemsFor1001 = new TreeSet<MetaItem>();
      // Add user ACL.
      List<String> users1001 = Arrays.asList("peter", "bart", "simon");
      metaItemsFor1001.add(MetaItem.permittedUsers(users1001));
      // Add group ACL.
      List<String> groups1001 = Arrays.asList("support", "sales");
      metaItemsFor1001.add(MetaItem.permittedGroups(groups1001));
      // Add custom meta items.
      metaItemsFor1001.add(MetaItem.raw("my-special-key", "my-custom-value"));
      metaItemsFor1001.add(MetaItem.raw("date", "not soon enough"));
      // Make metadata object, which checks items for consistency.
      Metadata metadataFor1001 = new Metadata(metaItemsFor1001);
      // Add our doc id with metadata to list.
      mockDocInfos.add(new DocInfo(new DocId("1001"), metadataFor1001));
    } catch (IllegalArgumentException e) {
      // Thrown by meta items and by metadata constructor.
      log.log(Level.SEVERE, "failed to make metadata" , e);
      // TODO(pjo): Get failed doc ids into dashboard.
    }
  
    try {
      // Another example.
      Set<MetaItem> metaItemsFor1002 = new TreeSet<MetaItem>();
      // A document that's not public and has no ACLs causes head requests.
      metaItemsFor1002.add(MetaItem.isNotPublic());
      // Set display URL.
      metaItemsFor1002.add(MetaItem.displayUrl("http://www.google.com"));
      // Add custom meta items.
      metaItemsFor1002.add(MetaItem.raw("date", "better never than late"));
      // Make metadata object, which checks items for consistency.
      Metadata metadataFor1002 = new Metadata(metaItemsFor1002);
      // Add our doc id with metadata to list.
      mockDocInfos.add(new DocInfo(new DocId("1002"), metadataFor1002));
    } catch (IllegalArgumentException e) {
      // Thrown by meta items and by metadata constructor.
      log.log(Level.SEVERE, "failed to make metadata" , e);
      // TODO(pjo): Get failed doc ids into dashboard.
    }

    pusher.pushDocInfos(mockDocInfos);
  }

  /** Gives the bytes of a document referenced with id. */
  @Override
  public void getDocContent(Request req, Response resp) throws IOException {
    DocId id = req.getDocId();
    String str;
    if ("1001".equals(id.getUniqueId())) {
      str = "Document 1001 says hello and apple orange";
    } else if ("1002".equals(id.getUniqueId())) {
      str = "Document 1002 says hello and banana strawberry";
    } else {
      throw new FileNotFoundException(id.getUniqueId());
    }
    // Must get the OutputStream after any possibility of throwing a
    // FileNotFoundException
    OutputStream os = resp.getOutputStream();
    os.write(str.getBytes(encoding));
  }

  /** Call default main for adaptors. */
  public static void main(String[] args) {
    AbstractAdaptor.main(new AdaptorWithMetadataTemplate(), args);
  }
}
