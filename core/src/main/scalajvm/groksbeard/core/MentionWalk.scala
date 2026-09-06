package groksbeard.core

object MentionWalk:
  def fromDisk(cwd: String, query: String, limit: Int = MentionSearch.FileLimit): List[MentionFile] =
    MentionSearch.pattern(query) match
      case None    => Nil
      case Some(_) =>
        val root = java.nio.file.Path.of(cwd).toAbsolutePath.normalize
        if !java.nio.file.Files.isDirectory(root) then Nil
        else
          val needle = query.trim.toLowerCase
          val skip   = Set("node_modules", ".git", "target", "dist")
          val acc    = scala.collection.mutable.ListBuffer.empty[MentionFile]
          val walk   = java.nio.file.Files.walk(root)
          try
            walk.forEach { p =>
              if acc.size < limit * 8 && java.nio.file.Files.isRegularFile(p) then
                val rel   = root.relativize(p).toString.replace('\\', '/')
                val parts = rel.split('/').toList
                if !parts.exists(skip.contains) && rel.toLowerCase.contains(needle) then
                  acc += MentionFile(rel, p.toString)
            }
          finally walk.close()
          MentionSearch.rank(acc.toList, query).take(limit)
        end if
end MentionWalk
