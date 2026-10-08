package com.example.homeezch
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class FileOperationsTest {
 private fun withTree(test:(File)->Unit) { val root=Files.createTempDirectory("ezch-files-").toFile();try { test(root) } finally { root.deleteRecursively() } }
 @Test fun copyAndMovePreserveContents()=withTree { root ->
  val a=File(root,"a").apply { mkdir() };File(a,"note.txt").writeText("sample");val dest=File(root,"dest").apply{mkdir()}
  FileOperations.copy(a,dest,false);assertEquals("sample",File(dest,"a/note.txt").readText());assertTrue(a.exists())
  val next=File(root,"next").apply{mkdir()};FileOperations.copy(a,next,true);assertFalse(a.exists());assertEquals("sample",File(next,"a/note.txt").readText())
 }
 @Test fun rejectsInvalidNamesAndNestedCopies()=withTree { root ->
  for(name in listOf("",".","..","a/b","a\\b")) assertThrows(Exception::class.java){FileOperations.child(root,name)}
  val a=File(root,"a").apply{mkdir()};val sub=File(a,"sub").apply{mkdir()};assertThrows(Exception::class.java){FileOperations.copy(a,sub,false)}
 }
 @Test fun copyAndRenameNeverOverwrite()=withTree { root ->
  val a=File(root,"a.txt").apply{writeText("first")};val dest=File(root,"dest").apply{mkdir()};File(dest,"a.txt").writeText("second")
  assertThrows(Exception::class.java){FileOperations.copy(a,dest,false)};assertEquals("second",File(dest,"a.txt").readText())
  File(root,"b.txt").writeText("other");assertThrows(Exception::class.java){FileOperations.rename(a,"b.txt")};assertEquals("first",a.readText())
 }
 @Test fun linksCannotEscapeCopyOrDeletion()=withTree { root ->
  val outside=File(root,"outside.txt").apply{writeText("keep")};val folder=File(root,"folder").apply{mkdir()};try { Files.createSymbolicLink(File(folder,"link").toPath(),outside.toPath()) } catch (e: java.nio.file.FileSystemException) { org.junit.Assume.assumeNoException("Host does not allow symlinks; Android test covers this", e) }
  val dest=File(root,"dest").apply{mkdir()};assertThrows(Exception::class.java){FileOperations.copy(folder,dest,false)};assertThrows(Exception::class.java){FileOperations.delete(folder)};assertEquals("keep",outside.readText())
 }
}
