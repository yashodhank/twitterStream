/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.twitter.TwitterSource;

import org.apache.flink.util.Collector;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import com.twitter.hbc.core.endpoint.StatusesFilterEndpoint;
import java.util.ArrayList;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.List;

import static org.apache.hadoop.yarn.webapp.hamlet.HamletSpec.Media.print;

/**
 * Implements the "TwitterStream" program that computes a most used word
 * occurrence over JSON objects in a streaming fashion.
 * <p>
 * The input is a Tweet stream from a TwitterSource.
 * </p>
 * <p>
 * Usage: <code>Usage: TwitterExample [--output <path>]
 * [--twitter-source.consumerKey <key> --twitter-source.consumerSecret <secret> --twitter-source.token <token> --twitter-source.tokenSecret <tokenSecret>]</code><br>
 *
 * If no parameters are provided, the program is run with default data from
 * {@link TwitterExampleData}.
 * </p>
 * <p>
 * This example shows how to:
 * <ul>
 * <li>acquire external data,
 * <li>use in-line defined functions,
 * <li>handle flattened stream inputs.
 * </ul>
 */
public class TwitterExample {

    // *************************************************************************
    // PROGRAM
    // *************************************************************************
    public static ArrayList<String> initArrayList(String path){
        //TODO get stop words from path.
        ArrayList<String> stopWords = new ArrayList<String>();
        //Add some stop words for example
        stopWords.add("from");
        stopWords.add("to");
        stopWords.add("of");
        stopWords.add("a");
        stopWords.add("rt");
        stopWords.add("am");
        return stopWords;

    }
    public static void main(String[] args) throws Exception {
        System.out.println("Working Directory = " +
                System.getProperty("user.dir"));
        // Checking input parameters
        final ParameterTool params = ParameterTool.fromPropertiesFile("myFile.properties");
        System.out.println("Usage: TwitterExample [--output <path>] " +
                "[--twitter-source.consumerKey <key> --twitter-source.consumerSecret <secret> --twitter-source.token <token> --twitter-source.tokenSecret <tokenSecret>]");

        // set up the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // make parameters available in the web interface
        env.getConfig().setGlobalJobParameters(params);

        env.setParallelism(params.getInt("parallelism", 1));

        //DataStream<String> streamSource = env.addSource(new TwitterSource("/myFile.properties"));
        System.out.println(" This is the param" + params.getProperties());

        // get input data
        DataStream<String> streamSource;

        if (params.has(TwitterSource.CONSUMER_KEY) &&
                params.has(TwitterSource.CONSUMER_SECRET) &&
                params.has(TwitterSource.TOKEN) &&
                params.has(TwitterSource.TOKEN_SECRET)
                ) {

            //streamSource = env.addSource(new TwitterSource(params.getProperties()));
            List<String> theList = new ArrayList<String>();

            //Find tweets about Trump and Clinton
            theList.add("trump");
            theList.add("clinton");
            TwitterSource twitterA = new TwitterSource(params.getProperties());
            TwitterSourceOpt.FilterEndpoint i = new TwitterSourceOpt.FilterEndpoint(theList);
            twitterA.setCustomEndpointInitializer(i);

            streamSource = env.addSource(twitterA);

        } else {
            System.out.println("Executing TwitterStream example with default props.");
            System.out.println("Use --twitter-source.consumerKey <key> --twitter-source.consumerSecret <secret> " +
                    "--twitter-source.token <token> --twitter-source.tokenSecret <tokenSecret> specify the authentication info.");
            // get default test text data
            streamSource = env.fromElements(TwitterExampleData.TEXTS);
        }
        final ArrayList<String> stopWords = initArrayList("stopwords.txt");

        DataStream<Tuple2<String, Integer>> tweets = streamSource
                // selecting English tweets and splitting to (word, 1)
                .flatMap(new SelectEnglishAndTokenizeFlatMap("text"))
                // group by words and sum their occurrences
                .keyBy(0).sum(1);
        //Get locations
        DataStream<Tuple2<String, Integer>> locations = streamSource.flatMap(new SelectEnglishAndTokenizeFlatMap("location")).keyBy(0).sum(1);
        //Filter out stop words and words with less than 19 mentions
        tweets = tweets.filter(new FilterFunction<Tuple2<String, Integer>>() {
            public boolean filter(Tuple2<String, Integer> value) { int s = value.getField(1); return s > 19; }
        }).filter(new FilterFunction<Tuple2<String, Integer>>(){
            public boolean filter(Tuple2<String,Integer> value){
                String word = value.getField(0);
                return !stopWords.contains(word);

            }
        });



        // emit result
        if (params.has("output")) {
            tweets.writeAsText(params.get("output"));
        } else {
            System.out.println("Printing result to stdout. Use --output to specify output path.");
            //tweets.print();
            locations.print();
        }

        // execute program
        env.execute("Twitter Streaming Example");
    }

    // *************************************************************************
    // USER FUNCTIONS
    // *************************************************************************

    /**
     * Deserialize JSON from twitter source
     *
     * <p>
     * Implements a string tokenizer that splits sentences into words as a
     * user-defined FlatMapFunction. The function takes a line (String) and
     * splits it into multiple pairs in the form of "(word,1)" ({@code Tuple2<String,
     * Integer>}).
     */
    public static class SelectEnglishAndTokenizeFlatMap implements FlatMapFunction<String, Tuple2<String, Integer>> {
        private static final long serialVersionUID = 1L;
        private String fieldName;
        private transient ObjectMapper jsonParser;
        SelectEnglishAndTokenizeFlatMap(String fieldName1){
            fieldName=fieldName1;
        }
        /**
         * Select the language from the incoming JSON text
         */
        public void flatMap(String value, Collector<Tuple2<String, Integer>> out) throws Exception {
            if(jsonParser == null) {
                jsonParser = new ObjectMapper();
            }
            JsonNode jsonNode = jsonParser.readValue(value, JsonNode.class);
            //System.out.println(jsonNode);
            boolean isEnglish = jsonNode.has("user") && jsonNode.get("user").has("lang") && jsonNode.get("user").get("lang").getValueAsText().equals("en");
            boolean hasText = jsonNode.has(fieldName);
            if (isEnglish && hasText) {

                StringTokenizer tokenizer = new StringTokenizer(jsonNode.get(fieldName).getValueAsText());

                // split the message
                while (tokenizer.hasMoreTokens()) {
                    String result = tokenizer.nextToken().replaceAll("\\s*", "").toLowerCase();

                    if (!result.equals("")) {
                        out.collect(new Tuple2<String,Integer>(result, 1));
                    }
                }
            }
        }

    }



}
