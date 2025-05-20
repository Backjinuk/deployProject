//package com.deployProject.util
//
//import com.deployProject.deploy.domain.site.FileStatusType
//import java.io.File
//import java.io.IOException
//import java.text.SimpleDateFormat
//import java.time.LocalDate
//import java.time.format.DateTimeFormatter
//import java.util.Date
//
//object SvnInfoCli {
//
//
//    @Throws(IOException::class)
//    fun run(
//        repoPath: String,
//        since: LocalDate,
//        until: LocalDate,
//        fileStatusType: FileStatusType,
//        deployServerDir: String
//    ) {
//
//        val svnDir = parseSvnDir(repoPath)
//        val workTree = svnDir.parentFile
//        val outputZip = determineOutputZip(svnDir)
//
//
////       val svn =
//
//
//    }
//
//
//    private fun parseSvnDir(repoPath: String): File = File(repoPath).apply {
//        if (!exists() || !File(this, "config").exists()) {
//            error("ERROR: Not a valid Git repository: ${absolutePath}")
//        }
//    }
//
//    private fun determineOutputZip(svnDir: File): File
//    {
//        val date = SimpleDateFormat("yyyyMMdd").format(Date())
//        return File(svnDir.parentFile, "$date.zip")
//    }
//
//
//
//
//    /**
//     * 진입점: <gitDir> [sinceDate] [untilDate] [fileStatusType]
//     */
//    @JvmStatic
//    fun main(args: Array<String>) {
//
//        val repoPath = args.getOrNull(0)
//            ?: error("Usage: java -jar git-info-cli.jar <gitDir> [sinceDate] [untilDate] [fileStatusType]")
//
//        val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd")
//        val since = parseDateArg(args.getOrNull(2), dateFmt)
//        val until = parseDateArg(args.getOrNull(3), dateFmt)
//        val statusType = parseStatusType(args.getOrNull(4))
//        val deployServerDir = args.getOrNull(5)?.takeIf { it.isNotBlank() } ?: "/home/bjw/deployProject/."
//
//        SvnInfoCli.run(repoPath, since, until, statusType, deployServerDir)
//    }
//}