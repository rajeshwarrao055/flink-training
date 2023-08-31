/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.training.exercises.longrides;

import jdk.jfr.ValueDescriptor;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimerService;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.sink.PrintSinkFunction;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.training.exercises.common.datatypes.TaxiRide;
import org.apache.flink.training.exercises.common.sources.TaxiRideGenerator;
import org.apache.flink.training.exercises.common.utils.MissingSolutionException;
import org.apache.flink.util.Collector;

import java.time.Duration;

/**
 * The "Long Ride Alerts" exercise.
 *
 * <p>The goal for this exercise is to emit the rideIds for taxi rides with a duration of more than
 * two hours. You should assume that TaxiRide events can be lost, but there are no duplicates.
 *
 * <p>You should eventually clear any state you create.
 */
public class LongRidesExercise {
    private final SourceFunction<TaxiRide> source;
    private final SinkFunction<Long> sink;

    /** Creates a job using the source and sink provided. */
    public LongRidesExercise(SourceFunction<TaxiRide> source, SinkFunction<Long> sink) {
        this.source = source;
        this.sink = sink;
    }

    /**
     * Creates and executes the long rides pipeline.
     *
     * @return {JobExecutionResult}
     * @throws Exception which occurs during job execution.
     */
    public JobExecutionResult execute() throws Exception {

        // set up streaming execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // start the data generator
        DataStream<TaxiRide> rides = env.addSource(source);

        // the WatermarkStrategy specifies how to extract timestamps and generate watermarks
        WatermarkStrategy<TaxiRide> watermarkStrategy =
                WatermarkStrategy.<TaxiRide>forBoundedOutOfOrderness(Duration.ofSeconds(60))
                        .withTimestampAssigner(
                                (ride, streamRecordTimestamp) -> ride.getEventTimeMillis());

        // create the pipeline
        rides.assignTimestampsAndWatermarks(watermarkStrategy)
                .keyBy(ride -> ride.rideId)
                .process(new AlertFunction())
                .addSink(sink);

        // execute the pipeline and return the result
        return env.execute("Long Taxi Rides");
    }

    /**
     * Main method.
     *
     * @throws Exception which occurs during job execution.
     */
    public static void main(String[] args) throws Exception {
        LongRidesExercise job =
                new LongRidesExercise(new TaxiRideGenerator(), new PrintSinkFunction<>());

        job.execute();
    }

    @VisibleForTesting
    public static class AlertFunction extends KeyedProcessFunction<Long, TaxiRide, Long> {
        private transient MapState<String , Long> startEndTime;
        private static final String START_TIME = "START_TIME";
        private static final String END_TIME = "END_TIME";

        @Override
        public void open(Configuration config) throws Exception {
            /*
            getRuntimeContext().getState(new ValueStateDescriptor<>("startTimeState", Float.class));
            getRuntimeContext().getState(new ValueStateDescriptor<>("endTimeState", Float.class));
            */
            startEndTime = getRuntimeContext().getMapState(
                    new MapStateDescriptor<>("startEndTime", String.class, Long.class));
        }

        @Override
        public void processElement(TaxiRide ride, Context context, Collector<Long> out)
                throws Exception {
            TimerService timerService = context.timerService();
            long eventTime = ride.getEventTimeMillis();
            if(eventTime <= timerService.currentWatermark()) {
                // late event, dropping
            } else {
                long twoHoursInMillis = 2 * 60 * 60 * 1000;
                long endOfWindow = eventTime + twoHoursInMillis;
                if(ride.isStart) {
                    startEndTime.put(START_TIME, ride.getEventTimeMillis());
                    if(startEndTime.contains(END_TIME)) {
                        long timerWindow = startEndTime.get(END_TIME) + twoHoursInMillis;
                        long duration = Math.abs(startEndTime.get(START_TIME) - startEndTime.get(END_TIME));
                        if(duration > 2 * 60 * 60 * 1000) {
                            out.collect(ride.rideId);
                        }
                        timerService.deleteEventTimeTimer(timerWindow);
                        startEndTime.clear();
                    } else {
                        timerService.registerEventTimeTimer(endOfWindow);
                    }
                } else {
                    startEndTime.put(END_TIME, ride.getEventTimeMillis());
                    if(startEndTime.contains(START_TIME)) {
                        long timerWindow = startEndTime.get(START_TIME) + twoHoursInMillis;

                        long duration = Math.abs(startEndTime.get(START_TIME) - startEndTime.get(END_TIME));
                        if(duration > 2 * 60 * 60 * 1000) {
                            out.collect(ride.rideId);
                        }
                        timerService.deleteEventTimeTimer(timerWindow);
                        startEndTime.clear();
                    } else {
                        timerService.registerEventTimeTimer(endOfWindow);
                    }
                }
            }
        }

        @Override
        public void onTimer(long timestamp, OnTimerContext context, Collector<Long> out)
                throws Exception {
            Long rideId = context.getCurrentKey();
            if(startEndTime.contains(START_TIME) && startEndTime.contains(END_TIME)) {
                long duration = Math.abs(startEndTime.get(START_TIME) - startEndTime.get(END_TIME));
                if(duration > 2 * 60 * 60 * 1000) {
                    out.collect(rideId);
                }
            } else {
                out.collect(rideId);
            }
        }
    }
}
