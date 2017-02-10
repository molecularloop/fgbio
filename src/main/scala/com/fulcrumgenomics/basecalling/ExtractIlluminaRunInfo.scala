/*
 * The MIT License
 *
 * Copyright (c) 2017 Fulcrum Genomics LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package com.fulcrumgenomics.basecalling

import com.fulcrumgenomics.FgBioDef.{DirPath, FilePath}
import com.fulcrumgenomics.cmdline.{ClpGroups, FgBioTool}
import com.fulcrumgenomics.util.{Io, ReadStructure, SampleBarcode, Template}
import htsjdk.samtools.util.Iso8601Date
import dagr.sopt.{arg, clp}

@clp(group=ClpGroups.Basecalling, description=
  """
    |Extracts information about an Illumina sequencing run from the RunInfo.xml.
    |
    |The output file will contain a header line and a single line containing the following columns:
    |1. Run Barcode: the unique identifier for the sequencing run and flowcell, stored as "<instrument-name>_<flowcell-barcode>".
    |2. Flowcell Barcode: the flowcell barcode.
    |3. Run Date: the date of the sequencing run.
    |4. Read Structure: the description of the logical structure of cycles within the sequencing run, including which cycles
    |   correspond to sample barcodes, molecular barcodes, template bases, and bases that should be skipped.
    |5. Number of Lanes: the number of lanes in the flowcell.
  """)
class ExtractIlluminaRunInfo
(
  @arg(flag="i", doc="The input RunInfo.xml typically found in the run folder.") val input: FilePath,
  @arg(flag="o", doc="The output file.") val output: FilePath,
  @arg(flag="d", doc="The delimiter to use") val delim: String = "\t"
) extends FgBioTool {

  Io.assertReadable(input)
  Io.assertCanWriteFile(output)

  override def execute(): Unit = {
    val info = RunInfo(runInfo=input)
    val lines = Seq(
      ExtractIlluminaRunInfo.HeaderColumns,
      Seq(info.runBarcode, info.flowcellBarcode, info.runDate, info.readStructure, info.numLanes).map(_.toString)
    ).map(_.mkString(delim))
    Io.writeLines(output, lines)
  }
}

object ExtractIlluminaRunInfo {
  val HeaderColumns: Seq[String] = Seq("run_barcode", "flowcell_barcode", "run_date", "read_structure", "num_lanes")
}

/** Stores the result of parsing the run info file.
  *
  * @param runBarcode the unique identifier for the sequencing run and flowcell, stored as
  *                   "<instrument-name>_<flowcell-barcode>".
  * @param flowcellBarcode the flowcell barcode.
  * @param runDate the date of the sequencing run.
  * @param readStructure the description of the logical structure of cycles within the sequencing run, including which cycles
  *                      correspond to sample barcodes, molecular barcodes, template bases, and bases that should be skipped.
  * @param numLanes the number of lanes in the flowcell.
  */
case class RunInfo
( runBarcode: String,
  flowcellBarcode: String,
  runDate: Iso8601Date,
  readStructure: ReadStructure,
  numLanes: Int
)

private object RunInfo {
  /** Parses the run info file for the flowcell barcode, instrument, run date, and read structure.
    *
    * @param runInfo the path to the RunInfo.xml file, typically in the run folder.
    */
  def apply(runInfo: FilePath): RunInfo = {
    import scala.xml.XML
    val xml = XML.loadFile(runInfo.toFile)
    val flowcellBarcode = (xml \\ "RunInfo" \\ "Run" \\ "Flowcell").text
    val instrument      = (xml \\ "RunInfo" \\ "Run" \\ "Instrument").text
    val runDate         = (xml \\ "RunInfo" \\ "Run" \\ "Date").text
    val segments        = (xml \\ "RunInfo" \\ "Run" \\ "Reads" \\ "Read").map { read =>
      val isIndexedRead = (read \ "@IsIndexedRead").text.equals("Y")
      val numCycles     = (read \ "@NumCycles").text.toInt
      if (isIndexedRead) SampleBarcode(offset=0, length=numCycles) else Template(offset=0, length=numCycles)
    }
    val readStructure = new ReadStructure(segments, resetOffsets=true)
    val numLanes = (xml \\ "RunInfo" \\ "Run" \\ "FlowcellLayout" \ "@LaneCount").text.toInt

    RunInfo(
      runBarcode      = s"${instrument}_$flowcellBarcode",
      flowcellBarcode = flowcellBarcode,
      runDate         = formatDate(runDate),
      readStructure   = readStructure,
      numLanes        = numLanes
    )
  }

  /** Formats the given date string.  The date string should be six or eight characters long with formats YYMMDD or
    * YYYYMMDD respectively.
    */
  private def formatDate(date: String): Iso8601Date = {
    if (date.length == 6) new Iso8601Date("20" + date.substring(0,2) + "-" + date.substring(2,4) + "-" + date.substring(4))
    else if (date.length == 8) new Iso8601Date(date.substring(0,4) + "-" + date.substring(4,6) + "-" + date.substring(6))
    else throw new IllegalArgumentException(s"Could not parse date: $date")
  }
}