package org.apache.mahout.df.tools;

import org.apache.commons.cli2.CommandLine;
import org.apache.commons.cli2.Group;
import org.apache.commons.cli2.Option;
import org.apache.commons.cli2.OptionException;
import org.apache.commons.cli2.builder.ArgumentBuilder;
import org.apache.commons.cli2.builder.DefaultOptionBuilder;
import org.apache.commons.cli2.builder.GroupBuilder;
import org.apache.commons.cli2.commandline.Parser;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.mahout.common.CommandLineUtil;
import org.apache.mahout.common.RandomUtils;
import org.apache.mahout.df.data.DataConverter;
import org.apache.mahout.df.data.Dataset;
import org.apache.mahout.df.data.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Random;
import java.util.Scanner;

/**
 * This tool is used to uniformely distribute the class of all the tuples of the dataset over a given number of
 * partitions.
 */
public class UDistrib {

  private static final Logger log = LoggerFactory.getLogger(UDistrib.class);

  /**
   * Launch the uniform distribution tool. Requires the following command line arguments:<br>
   *
   * data : data path
   * dataset : dataset path
   * numpartitions : num partitions
   * output : output path
   *
   */
  public static void main(String[] args) throws IOException {

    DefaultOptionBuilder obuilder = new DefaultOptionBuilder();
    ArgumentBuilder abuilder = new ArgumentBuilder();
    GroupBuilder gbuilder = new GroupBuilder();

    Option dataOpt = obuilder.withLongName("data").withShortName("d")
        .withRequired(true).withArgument(
            abuilder.withName("data").withMinimum(1).withMaximum(1).create())
        .withDescription("Data path").create();

    Option datasetOpt = obuilder.withLongName("dataset").withShortName(
        "ds").withRequired(true).withArgument(
        abuilder.withName("dataset").withMinimum(1).create())
        .withDescription("Dataset path").create();

    Option outputOpt = obuilder.withLongName("output").withShortName("o")
        .withRequired(true).withArgument(
            abuilder.withName("output").withMinimum(1).withMaximum(1).create())
        .withDescription("Path to generated files").create();

    Option partitionsOpt = obuilder.withLongName("numpartitions").withShortName("p")
        .withRequired(true).withArgument(
            abuilder.withName("numparts").withMinimum(1).withMinimum(1).create())
        .withDescription("Number of partitions to create").create();
    Option helpOpt = obuilder.withLongName("help").withDescription(
        "Print out help").withShortName("h").create();

    Group group = gbuilder.withName("Options").withOption(dataOpt).withOption(
        outputOpt).withOption(datasetOpt).withOption(partitionsOpt).withOption(helpOpt).create();

    try {
      Parser parser = new Parser();
      parser.setGroup(group);
      CommandLine cmdLine = parser.parse(args);

      if (cmdLine.hasOption(helpOpt)) {
        CommandLineUtil.printHelp(group);
        return;
      }

      String data = cmdLine.getValue(dataOpt).toString();
      String dataset = cmdLine.getValue(datasetOpt).toString();
      int numPartitions = Integer.parseInt(cmdLine.getValue(partitionsOpt).toString());
      String output = cmdLine.getValue(outputOpt).toString();

      runTool(data, dataset, output, numPartitions);
    } catch (OptionException e) {
      System.err.println("Exception : " + e);
      CommandLineUtil.printHelp(group);
    }

  }

  private static void runTool(String dataStr, String datasetStr, String output, int numPartitions) throws IOException {

    Configuration conf = new Configuration();

    // TODO exception if numPArtitions <= 0

    // create a new file corresponding to each partition
    Path outputPath = new Path(output);
    FileSystem fs = outputPath.getFileSystem(conf);
    FSDataOutputStream[] files = new FSDataOutputStream[numPartitions];
    for (int p = 0; p < numPartitions; p++) {
      files[p] = fs.create(new Path(outputPath, String.format("part-%03d.data", p)));
    }

    Path datasetPath = new Path(datasetStr);
    Dataset dataset = Dataset.load(conf, datasetPath);

    // currents[label] = next partition file where to place the tuple
    int[] currents = new int[dataset.nblabels()];

    // currents is initialized randomly in the range [0, numpartitions[
    Random random = RandomUtils.getRandom();
    for (int c = 0; c < currents.length; c++) {
      currents[c] = random.nextInt(numPartitions);
    }

    // foreach tuple of the data
    Path dataPath = new Path(dataStr);
    FileSystem ifs = dataPath.getFileSystem(conf);
    FSDataInputStream input = ifs.open(dataPath);
    Scanner scanner = new Scanner(input);
    DataConverter converter = new DataConverter(dataset);

    int id = 0;
    while (scanner.hasNextLine()) {
      if ((id % 1000)==0) {
        log.info("currentId : " + id);
      }
      
      String line = scanner.nextLine();
      if (line.isEmpty())
        continue; // skip empty lines

      // write the tuple in files[tuple.label]
      Instance instance = converter.convert(id++, line);
      int label = instance.label;
      files[currents[label]].writeBytes(line);
      files[currents[label]].writeChar('\n');

      // update currents
      currents[label]++;
      if (currents[label] == numPartitions)
        currents[label] = 0;
    }

    // close all the files.
    scanner.close();
    for (FSDataOutputStream file : files)
      file.close();
  }

}
