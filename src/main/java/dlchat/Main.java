package dlchat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.ArrayUtils;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.BackpropType;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration.GraphBuilder;
import org.deeplearning4j.nn.conf.GradientNormalization;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.graph.rnn.DuplicateToTimeSeriesVertex;
import org.deeplearning4j.nn.conf.graph.rnn.LastTimeStepVertex;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.EmbeddingLayer;
import org.deeplearning4j.nn.conf.layers.GravesLSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.graph.vertex.GraphVertex;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions;

public class Main {

    /*
     * This is a seq2seq encoder-decoder LSTM model made according to the Google's paper: [1] The model tries to predict the next dialog
     * line using the provided one. It learns on the Cornell Movie Dialogs corpus. Unlike simple char RNNs this model is more sophisticated
     * and theoretically, given enough time and data, can deduce facts from raw text. Your mileage may vary. This particular code is based
     * on AdditionRNN but heavily changed to be used with a huge amount of possible tokens (10-20k), it also utilizes the decoder input
     * unlike AdditionRNN.
     * 
     * Special tokens used:
     * 
     * <unk> - replaces any word or other token that's not in the dictionary (too rare to be included or completely unknown)
     * 
     * <eos> - end of sentence, used only in the output to stop the processing; the model input and output length is limited by the ROW_SIZE
     * constant.
     * 
     * <go> - used only in the decoder input as the first token before the model produced anything
     * 
     * The architecture is like this: Input => Embedding Layer => Encoder => Decoder => Output (softmax)
     * 
     * The encoder layer produces a so called "thought vector" that contains a compressed representation of the input. Depending on that
     * vector the model produces different sentences even if they start with the same token. There's one more input, connected directly to
     * the decoder layer, it's used to provide the previous token of the output. For the very first output token we send a special <go>
     * token there, on the next iteration we use the token that the model produced the last time. On the training stage everything is
     * simple, we apriori know the desired output so the decoder input would be the same token set prepended with the <go> token and without
     * the last <eos> token. Example:
     * 
     * Input: "how" "do" "you" "do" "?"
     * 
     * Output: "I'm" "fine" "," "thanks" "!" "<eos>"
     * 
     * Decoder: "<go>" "I'm" "fine" "," "thanks" "!"
     * 
     * Actually, the input is reversed as per [2], the most important words are usually in the beginning of the phrase and they would get
     * more weight if supplied last (the model "forgets" tokens that were supplied "long ago"). The output and decoder input sequence
     * lengths are always equal. The input and output could be of any length (less than ROW_SIZE) so for purpose of batching we mask the
     * unused part of the row. The encoder and decoder networks work sequentially. First the encoder creates the thought vector, that is the
     * last activations of the layer. Those activations are then duplicated for as many time steps as there are elements in the output so
     * that every output element can have its own copy of the thought vector. Then the decoder starts working. It receives two inputs, the
     * thought vector made by the encoder and the token that it _should have produced_ (but usually it outputs something else so we have our
     * loss metric and can compute gradients for the backward pass) on the previous step (or <go> for the very first step). These two
     * vectors are simply concatenated by the merge vertex. The decoder's output goes to the softmax layer and that's it.
     * 
     * The test phase is much more tricky. We don't know the decoder input because we don't know the output yet (unlike in the train phase),
     * it could be anything. So we can't use methods like outputSingle() and have to do some manual work. Actually, we can but it would
     * require full restarts of the entire process, it's super slow and ineffective.
     * 
     * First, we do a single feed forward pass for the input with a single decoder element, <go>. We don't need the actual activations
     * except the "thought vector". It resides in the second merge vertex input (named "dup"). So we get it and store for the entire
     * response generation time. Then we put the decoder input (<go> for the first iteration) and the thought vector to the merge vertex
     * inputs and feed it forward. The result goes to the decoder layer, now with rnnTimeStep() method so that the internal layer state is
     * updated for the next iteration. The result is fed to the output softmax layer and then we sample it randomly (not with argMax(), it
     * tends to give a lot of same tokens in a row). The resulting token is show to the user according to the dictionary and then goes to
     * the next iteration as the decoder input and so on until we get <eos>.
     * 
     * [1] https://arxiv.org/abs/1506.05869 A Neural Conversational Model
     * 
     * [2] https://papers.nips.cc/paper/5346-sequence-to-sequence-learning-with-neural-networks.pdf Sequence to Sequence Learning with
     * Neural Networks
     */

    public final Map<String, Double> dict = new HashMap<>();
    public final Map<Double, String> revDict = new HashMap<>();
    private final String CHARS = "-\\/_&" + LogProcessor.SPECIALS;
    private List<List<Double>> logs = new ArrayList<>();
    private Random rng = new Random();
    // RNN dimensions
    public static final int HIDDEN_LAYER_WIDTH = 512; // this is purely empirical, affects performance and VRAM requirement
    private static final int EMBEDDING_WIDTH = 128; // one-hot vectors will be embedded to more dense vectors with this width
    private static final String CORPUS_FILENAME = "movie_lines.txt"; // filename of data corpus to learn
    private static final String MODEL_FILENAME = "rnn_train.zip"; // filename of the model
    private static final String BACKUP_MODEL_FILENAME = "rnn_train.bak.zip"; // filename of the previous version of the model (backup)
    private static final int MINIBATCH_SIZE = 32;
    private static final Random rnd = new Random(new Date().getTime());
    private static final long SAVE_EACH_MS = TimeUnit.MINUTES.toMillis(5); // save the model with this period
    private static final long TEST_EACH_MS = TimeUnit.MINUTES.toMillis(1); // test the model with this period
    private static final int MAX_DICT = 20000; // this number of most frequent words will be used, unknown words (that are not in the
                                               // dictionary) are replaced with <unk> token
    private static final int TBPTT_SIZE = 25;
    private static final double LEARNING_RATE = 1e-1;
    private static final double L2 = 1e-3;
    private static final double RMS_DECAY = 0.95;
    private static final int ROW_SIZE = 40; // maximum line length in tokens
    private static final int GC_WINDOW = 2000; // delay between garbage collections, try to reduce if you run out of VRAM or increase for
                                               // better performance
    private static final int MACROBATCH_SIZE = 20;
    private ComputationGraph net;

    public static void main(String[] args) throws IOException {
        new Main().run(args);
    }

    private void run(String[] args) throws IOException {
        Nd4j.ENFORCE_NUMERICAL_STABILITY = true;
        Nd4j.getMemoryManager().setAutoGcWindow(GC_WINDOW);

        double idx = 3.0;
        dict.put("<unk>", 0.0);
        revDict.put(0.0, "<unk>");
        dict.put("<eos>", 1.0);
        revDict.put(1.0, "<eos>");
        dict.put("<go>", 2.0);
        revDict.put(2.0, "<go>");
        for (char c : CHARS.toCharArray()) {
            if (!dict.containsKey(c)) {
                dict.put(String.valueOf(c), idx);
                revDict.put(idx, String.valueOf(c));
                ++idx;
            }
        }
        prepareData(idx);

        NeuralNetConfiguration.Builder builder = new NeuralNetConfiguration.Builder();
        builder.iterations(1).learningRate(LEARNING_RATE).rmsDecay(RMS_DECAY)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).miniBatch(true).updater(Updater.RMSPROP)
                .weightInit(WeightInit.XAVIER).gradientNormalization(GradientNormalization.RenormalizeL2PerLayer);

        GraphBuilder graphBuilder = builder.graphBuilder().pretrain(false).backprop(true).backpropType(BackpropType.Standard)
                .tBPTTBackwardLength(TBPTT_SIZE).tBPTTForwardLength(TBPTT_SIZE);
        graphBuilder.addInputs("inputLine", "decoderInput")
                .setInputTypes(InputType.recurrent(dict.size()), InputType.recurrent(dict.size()))
                .addLayer("embeddingEncoder", new EmbeddingLayer.Builder().nIn(dict.size()).nOut(EMBEDDING_WIDTH).build(), "inputLine")
                .addLayer("encoder",
                        new GravesLSTM.Builder().nIn(EMBEDDING_WIDTH).nOut(HIDDEN_LAYER_WIDTH).activation(Activation.TANH).build(),
                        "embeddingEncoder")
                .addVertex("thoughtVector", new LastTimeStepVertex("inputLine"), "encoder")
                .addVertex("dup", new DuplicateToTimeSeriesVertex("decoderInput"), "thoughtVector")
                .addLayer("decoder",
                        new GravesLSTM.Builder().nIn(dict.size() + HIDDEN_LAYER_WIDTH).nOut(HIDDEN_LAYER_WIDTH).activation(Activation.TANH)
                                .build(),
                        "decoderInput", "dup")
                .addLayer("output", new RnnOutputLayer.Builder().nIn(HIDDEN_LAYER_WIDTH).nOut(dict.size()).activation(Activation.SOFTMAX)
                        .lossFunction(LossFunctions.LossFunction.MCXENT).build(), "decoder")
                .setOutputs("output");

        ComputationGraphConfiguration conf = graphBuilder.build();
        File networkFile = new File(MODEL_FILENAME);
        if (networkFile.exists()) {
            System.out.println("Loading the existing network...");
            net = ModelSerializer.restoreComputationGraph(networkFile);
            if (args.length == 0) {
                test();
            }
        } else {
            System.out.println("Creating a new network...");
            net = new ComputationGraph(conf);
            net.init();
        }

        if (args.length == 1 && args[0].equals("dialog")) {
            startDialog();
        } else {
            net.setListeners(new ScoreIterationListener(1));
            learn(networkFile);
        }
    }

    private void learn(File networkFile) throws IOException {
        long lastSaveTime = System.currentTimeMillis();
        long lastTestTime = System.currentTimeMillis();
        LogsIterator logsIterator = new LogsIterator(logs, MINIBATCH_SIZE, MACROBATCH_SIZE, dict.size(), ROW_SIZE, revDict);
        for (int epoch = 1; epoch < 10000; ++epoch) {
            System.out.println("Epoch " + epoch);
            int i = 0;
            String shift = System.getProperty("dlchat.shift");
            if (epoch == 1 && shift != null) {
                logsIterator.setCurrentBatch(Integer.valueOf(shift));
            } else {
                logsIterator.reset();
            }
            int lastPerc = 0;
            while (logsIterator.hasNextMacrobatch()) {
                long t1 = System.nanoTime();
                net.fit(logsIterator);
                long t2 = System.nanoTime();
                logsIterator.nextMacroBatch();
                System.out.println("Fit time: " + (t2 - t1));
                System.out.println("Batch = " + logsIterator.batch());
                int newPerc = (logsIterator.batch() * 100 / logsIterator.totalBatches());
                if (newPerc != lastPerc) {
                    System.out.println("Epoch complete: " + newPerc + "%");
                    lastPerc = newPerc;
                }
                if (System.currentTimeMillis() - lastSaveTime > SAVE_EACH_MS) {
                    saveModel(networkFile);
                    lastSaveTime = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - lastTestTime > TEST_EACH_MS) {
                    test();
                    lastTestTime = System.currentTimeMillis();
                }
            }
        }
    }

    private void startDialog() throws IOException {
        System.out.println("Dialog started.");
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("In> ");
                String line = "1 +++$+++ u11 +++$+++ m0 +++$+++ WALTER +++$+++ " + scanner.nextLine() + "\n";
                LogProcessor dialogProcessor = new LogProcessor(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)), ROW_SIZE,
                        false) {
                    @Override
                    protected void processLine(String lastLine) {
                        List<String> words = new ArrayList<>();
                        doProcessLine(lastLine, words, true);
                        List<Double> wordIdxs = new ArrayList<>();
                        if (processWords(words, wordIdxs)) {
                            System.out.print("Got words: ");
                            for (Double idx : wordIdxs) {
                                System.out.print(revDict.get(idx) + " ");
                            }
                            System.out.println();
                            System.out.print("Out> ");
                            output(wordIdxs, true, true);
                        }
                    }
                };
                dialogProcessor.setDict(dict);
                dialogProcessor.start();
            }
        }
    }

    private void saveModel(File networkFile) throws IOException {
        System.out.println("Saving the model...");
        File backup = new File(BACKUP_MODEL_FILENAME);
        if (networkFile.exists()) {
            if (backup.exists()) {
                backup.delete();
            }
            networkFile.renameTo(backup);
        }
        ModelSerializer.writeModel(net, networkFile, true);
        System.out.println("Done.");
    }

    private void test() {
        System.out.println("======================== TEST ========================");
        int selected = rnd.nextInt(logs.size());
        List<Double> rowIn = new ArrayList<>(logs.get(selected));
        System.out.print("In: ");
        for (Double idx : rowIn) {
            System.out.print(revDict.get(idx) + " ");
        }
        System.out.println();
        System.out.print("Out: ");
        output(rowIn, true, true);
        System.out.println("====================== TEST END ======================");
    }

    private void output(List<Double> rowIn, boolean printUnknowns, boolean stopOnEos) {
        net.rnnClearPreviousState();
        Collections.reverse(rowIn);
        INDArray in = Nd4j.create(ArrayUtils.toPrimitive(rowIn.toArray(new Double[0])), new int[] { 1, 1, rowIn.size() });
        double[] decodeArr = new double[dict.size()];
        decodeArr[2] = 1;
        INDArray decode = Nd4j.create(decodeArr, new int[] { 1, dict.size(), 1 });
        net.outputSingle(in, decode);
        org.deeplearning4j.nn.layers.recurrent.GravesLSTM decoder = (org.deeplearning4j.nn.layers.recurrent.GravesLSTM) net
                .getLayer("decoder");
        Layer output = net.getLayer("output");
        GraphVertex mergeVertex = net.getVertex("decoder-merge");
        INDArray thoughtVector = mergeVertex.getInputs()[1];
        for (int row = 0; row < ROW_SIZE; ++row) {
            mergeVertex.setInputs(decode, thoughtVector);
            INDArray merged = mergeVertex.doForward(false);
            INDArray activateDec = decoder.rnnTimeStep(merged);
            INDArray out = output.activate(activateDec, false);
            double d = rng.nextDouble();
            double sum = 0.0;
            int idx = -1;
            for (int s = 0; s < out.size(1); s++) {
                sum += out.getDouble(0, s, 0);
                if (d <= sum) {
                    idx = s;
                    if (printUnknowns || s != 0) {
                        System.out.print(revDict.get((double) s) + " ");
                    }
                    break;
                }
            }
            if (stopOnEos && idx == 1) {
                break;
            }
            double[] newDecodeArr = new double[dict.size()];
            newDecodeArr[idx] = 1;
            decode = Nd4j.create(newDecodeArr, new int[] { 1, dict.size(), 1 });
        }
        System.out.println();
    }

    private void prepareData(double idx) throws IOException, FileNotFoundException {
        System.out.println("Building the dictionary...");
        LogProcessor logProcessor = new LogProcessor(CORPUS_FILENAME, ROW_SIZE, true);
        logProcessor.start();
        Map<String, Double> freqs = logProcessor.getFreq();
        Set<String> dictSet = new TreeSet<>();
        Map<Double, Set<String>> freqMap = new TreeMap<>(new Comparator<Double>() {

            @Override
            public int compare(Double o1, Double o2) {
                return (int) (o2 - o1);
            }
        });
        for (Entry<String, Double> entry : freqs.entrySet()) {
            Set<String> set = freqMap.get(entry.getValue());
            if (set == null) {
                set = new TreeSet<>();
                freqMap.put(entry.getValue(), set);
            }
            set.add(entry.getKey());
        }
        int cnt = 0;
        dictSet.addAll(dict.keySet());
        for (Entry<Double, Set<String>> entry : freqMap.entrySet()) {
            for (String val : entry.getValue()) {
                if (dictSet.add(val) && ++cnt >= MAX_DICT) {
                    break;
                }
            }
            if (cnt >= MAX_DICT) {
                break;
            }
        }
        System.out.println("Dictionary is ready, size is " + dictSet.size());
        for (String word : dictSet) {
            if (!dict.containsKey(word)) {
                dict.put(word, idx);
                revDict.put(idx, word);
                ++idx;
            }
        }
        System.out.println("Total dictionary size is " + dict.size() + ". Processing the dataset...");
        // System.out.println(dict);
        logProcessor = new LogProcessor(CORPUS_FILENAME, ROW_SIZE, false) {
            @Override
            protected void processLine(String lastLine) {
                List<Double> wordIdxs = new ArrayList<>();
                ArrayList<String> words = new ArrayList<>();
                doProcessLine(lastLine, words, true);
                if (!words.isEmpty()) {
                    if (processWords(words, wordIdxs)) {
                        logs.add(wordIdxs);
                    }
                }
            }
        };
        logProcessor.setDict(dict);
        logProcessor.start();
        System.out.println("Done. Logs size is " + logs.size());
    }

}
