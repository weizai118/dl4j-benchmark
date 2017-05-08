package org.deeplearning4j.benchmarks;

import lombok.extern.slf4j.Slf4j;
import org.datavec.image.recordreader.ImageRecordReader;
import org.deeplearning4j.datasets.iterator.AsyncDataSetIterator;
import org.deeplearning4j.listeners.BenchmarkListener;
import org.deeplearning4j.listeners.BenchmarkReport;
import org.deeplearning4j.models.ModelSelector;
import org.deeplearning4j.models.ModelType;
import org.deeplearning4j.models.TestableModel;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.optimize.listeners.PerformanceListener;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.parallelism.ParallelWrapper;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.api.ops.executioner.OpExecutioner;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.profiler.OpProfiler;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Benchmarks popular CNN models using the CIFAR-10 dataset.
 */
@Slf4j
public abstract class BaseBenchmark {
    protected int listenerFreq = 10;
    protected int iterations = 1
            ;
    protected static Map<ModelType,TestableModel> networks;
    protected boolean train = true;

    public void benchmark(int height, int width, int channels, int numLabels, int batchSize, int seed, String datasetName,
                          DataSetIterator iter, ModelType modelType, boolean profile) throws Exception {
        long totalTime = System.currentTimeMillis();

        log.info("Building models for "+modelType+"....");
        networks = ModelSelector.select(modelType, height, width, channels, numLabels, seed, iterations);

        log.info("========================================");
        log.info("===== Benchmarking selected models =====");
        log.info("========================================");

        for (Map.Entry<ModelType, TestableModel> net : networks.entrySet()) {
            String dimensions = datasetName+" "+batchSize+"x"+channels+"x"+height+"x"+width;
            log.info("Selected: "+net.getKey().toString()+" "+dimensions);

            Model model = net.getValue().init();
            BenchmarkReport report = new BenchmarkReport(net.getKey().toString(), dimensions);
            report.setModel(model);

            // ADSI
            AsyncDataSetIterator asyncIter = new AsyncDataSetIterator(iter, 8, true);


            log.info("===== Benchmarking training iteration =====");
            profileStart(profile);
            if(model instanceof MultiLayerNetwork) {
                // warm-up
                for(int i = 0; i < 10; i++) {
                    if(asyncIter.hasNext()) asyncIter.next();
                    else asyncIter.reset();
                }

                // timing
                model.setListeners(new PerformanceListener(listenerFreq), new BenchmarkListener(report));
                ((MultiLayerNetwork) model).fit(asyncIter);
            }
            if(model instanceof ComputationGraph) {
                // warm-up
                for(int i = 0; i < 10; i++) {
                    if(asyncIter.hasNext()) asyncIter.next();
                    else asyncIter.reset();
                }

                // timing
                model.setListeners(new PerformanceListener(listenerFreq), new BenchmarkListener(report));
                ((ComputationGraph) model).fit(asyncIter);
            }
            profileEnd("Fit", profile);


            log.info("===== Benchmarking forward/backward pass =====");
            /*
                Notes: popular benchmarks will measure the time it takes to set the input and feed forward
                and backward. This is consistent with benchmarks seen in the wild like this code:
                https://github.com/jcjohnson/cnn-benchmarks/blob/master/cnn_benchmark.lua
             */
            iter.reset();

            long totalForward = 0;
            long totalBackward = 0;
            long nIterations = 0;
            if(model instanceof MultiLayerNetwork) {
                profileStart(profile);
                while(iter.hasNext()) {
                    try (MemoryWorkspace workspace = Nd4j.getWorkspaceManager().getAndActivateWorkspace(ComputationGraph.workspaceExternal)) {
                    DataSet ds = iter.next();
                    ds.migrate();
                    INDArray input = ds.getFeatures();
                    INDArray labels = ds.getLabels();

                        // forward
                        ((MultiLayerNetwork) model).setInput(input);
                        ((MultiLayerNetwork) model).setLabels(labels);
                        long forwardTime = System.nanoTime();
                        ((MultiLayerNetwork) model).feedForward();
                        if (Nd4j.getExecutioner() instanceof GridExecutioner)
                            ((GridExecutioner) Nd4j.getExecutioner()).flushQueueBlocking();
                        forwardTime = System.nanoTime() - forwardTime;
                        totalForward += (forwardTime / 1e6);

                        // backward
                        long backwardTime = 0;
                        Method m = MultiLayerNetwork.class.getDeclaredMethod("backprop"); // requires reflection
                        m.setAccessible(true);

                        backwardTime = System.nanoTime();
                        m.invoke(model);
                        backwardTime = System.nanoTime() - backwardTime;
                        totalBackward += (backwardTime / 1e6);

                        nIterations += 1;
                        if (nIterations % 100 == 0) log.info("Completed " + nIterations + " iterations");
                    }
                }
                profileEnd("Forward", profile);
            } else if(model instanceof ComputationGraph) {
                profileStart(profile);
                while(iter.hasNext()) {
                    DataSet ds = iter.next();
                    INDArray input = ds.getFeatures();
                    INDArray labels = ds.getLabels();

                    try (MemoryWorkspace workspace = Nd4j.getWorkspaceManager().getAndActivateWorkspace(ComputationGraph.workspaceExternal)) {

                        // forward
                        ((ComputationGraph) model).setInput(0, input);
                        ((ComputationGraph) model).setLabels(labels);
                        long forwardTime = System.nanoTime();
                        ((ComputationGraph) model).feedForward();
                        forwardTime = System.nanoTime() - forwardTime;
                        totalForward += (forwardTime / 1e6);

                        // backward
                        long backwardTime = System.nanoTime();
                        Method m = ComputationGraph.class.getDeclaredMethod("calcBackpropGradients", boolean.class, INDArray[].class);
                        m.setAccessible(true);
                        m.invoke(model, false, null);
                        backwardTime = System.nanoTime() - backwardTime;
                        totalBackward += (backwardTime / 1e6);

                        nIterations += 1;
                        if (nIterations % 100 == 0) log.info("Completed " + nIterations + " iterations");
                    }
                }
                profileEnd("Backward", profile);
            }
            report.setAvgFeedForward((double) totalForward / (double) nIterations);
            report.setAvgBackprop((double) totalBackward / (double) nIterations);


            log.info("=============================");
            log.info("===== Benchmark Results =====");
            log.info("=============================");

            System.out.println(report.getModelSummary());
            System.out.println(report.toString());
        }
    }

    private static void profileStart(boolean enabled){
        if(enabled){
            Nd4j.getExecutioner().setProfilingMode(OpExecutioner.ProfilingMode.ALL);
            OpProfiler.getInstance().reset();
        }
    }

    private static void profileEnd(String label, boolean enabled){
        if(enabled){
            log.info("==== " + label + " - OpProfiler Results ====");
            OpProfiler.getInstance().printOutDashboard();
        }
    }
}
