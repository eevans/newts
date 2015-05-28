/*
 * Copyright 2015, The OpenNMS Group
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opennms.newts.graphite;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.opennms.newts.graphite.GraphiteHandler.parseSample;

import org.junit.Test;
import org.opennms.newts.api.Sample;

public class GraphiteHandlerTest {

    @Test
    public void testParseSample() {
        Sample sample = parseSample("foo.bar.baz 5 10000");

        assertThat(sample.getResource().getId(), is("foo:bar"));
        assertThat(sample.getName(), is("baz"));
        assertThat(sample.getValue().intValue(), equalTo(5));
        assertThat(sample.getTimestamp().asSeconds(), equalTo(10000L));

        sample = parseSample("foo 5 10000");
        assertThat(sample.getResource().getId(), is("foo"));
        assertThat(sample.getName(), is("value"));

    }

}
