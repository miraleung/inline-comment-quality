# Inline Comment Quality

This is the replication package for the submission titled "Characterizing the
Quality of Inline Comments".  It contains our modified fork of Joern, the
scripts used to extract comments, and the code snippets we used in our study.
We've also included steps to clone the five codebases we analyzed (at our
snapshot hashes) and pre-process them.

## Joern Set-up
This is based on a fork of Joern that branched off at commit hash
`f08de36032fa833ed65de0edab6c400d5ce2685f`.

Please install JDK 11 and SBT as per [these
steps](https://www.scala-sbt.org/1.x/docs/Setup.html). Next, set up and build
Joern as follows:

```
cd joern
sbt stage
sbt "joerncli / stage"
```

## <a name="cloning-codebases"></a>Cloning Codebases
The following steps clones the codebases we used at the same snapshots we used
in our work.

```
CODEDIR=/tmp/codebases
cd $CODEDIR

# Hadoop
git clone https://github.com/apache/hadoop.git
pushd hadoop
git reset --hard 23e1511cd0f1594506d8d86461f5805c6fe46e92
popd

# Guava
git clone https://github.com/google/guava.git
pushd guava
git reset --hard 6da6def85e3ae7b1a851ca1a5de756b3eb2ca313
popd

# Spring
git clone https://github.com/spring-projects/spring-framework.git
pushd spring-framework
git reset --hard 8ccf05adeefa3fd2a377eba067a156ea163dd616
popd

# Junit
git clone https://github.com/junit-team/junit5.git
pushd junit5
git reset --hard 7416e56237d3eefc890d3b36164a1b4a22fd5941
popd

# JDK
git clone https://github.com/openjdk/jdk.git
pushd jdk
git reset --hard 649f2d8835027128c6c8cf37236808094a12a35f
popd

```

## Processing Codebases
This step removes unit tests from our codebases (see paper for more details).
We also made changes that did not affect the AST to work around certain Joern
bugs. For example, removing `super` from an invocation like `super.close()`
does not affect the fact that this line is a `CALL`-typed AST node.

In this section, we assume that the codebases have been cloned into `$CODEDIR`
as in the [above section](#cloning-codebases).

```
CODEDIR=/tmp/codebases
cd $CODEDIR
```

### <a name="all-codebases"></a>All Codebases
- Remove tests and `package-info.java`. As per [official
  documentation](https://docs.oracle.com/javase/specs/jls/se7/html/jls-7.html),
  the latter exists only for documentation purposes. Consequently, it should
  not contain any logic or any inline comments.

  ```
  for PROJECT in hadoop guava spring-framework junit5 jdk
  do
    pushd $CODEDIR/$PROJECT
    rm `find ./ -type f -name *Tests.java`
    rm `find ./ -type f -name package-info.java`
    popd
  done
  ```

### Hadoop
- Remove C++ files:

  ```
  pushd $CODEDIR/hadoop
  rm -r hadoop-hdfs-project/hadoop-hdfs-native-client`
  ```

- The following are workarounds for bugs in Joern:
  - In `hadoop/hadoop-yarn-project/hadoop-yarn/hadoop-yarn-common/src/main/java/org/apache/hadoop/yarn/webapp/hamlet2/Hamlet.java`:
    - Replace `this`  with `thiz` (e.g. `s/this/thiz`).
  - Repeat the previous step for `DBInputFormat.java` as well, but apply to two more inner classes.
  - In `hadoop/hadoop-mapreduce-project/hadoop-mapreduce-client/hadoop-mapreduce-client-core/src/main/java/org/apache/hadoop/mapred/lib/db/DBOutputFormat.java`, Make the following changes:
    - Remove all calls to super, except the constructor.

      ```
      super.close()  // Remove super
      super.getRecordWriter() // Remove super
      ```
    - Change the first nested protected class to not inherit from the extended type.

    ```
    public class DBOutputFormat<K  extends DBWritable, V>
        //extends org.apache.hadoop.mapreduce.lib.db.DBOutputFormat<K, V>
        implements OutputFormat<K, V> {

      /**
       * A RecordWriter that writes the reduce output to a SQL table
       */
      protected class DBRecordWriter
        //extends org.apache.hadoop.mapreduce.lib.db.DBOutputFormat<K, V>.DBRecordWriter
        implements RecordWriter<K, V> {
        // Code goes here.
      }
    }
    ```
### Spring
- Remove the following file, which does **not** have inline comments. This is
  needed to work around bugs in the Java parser.

  ```
  pushd $CODEDIR/spring-framework
  rm spring-core/src/main/java/org/springframework/aot/aot/hint/ReflectionHintsPredicates.java
  ```
### JUnit
- Remove remaining tests not covered in the [common processing step](#all-codebases)

  ```
  rm `find ./ -type f -name Tests.java
  ```

- Remove the following file, which does **not** have inline comments. This is
  needed to work around bugs in the Java parser.

  ```
  pushd $CODEDIR/junit5
  rm junit5/junit-platform-engine/src/main/java/org/junit/platform/engine/Filter.java
  ```

### JDK
- Remove non-Java files in `jdk/src/hotspot`:

  ```
  pushd $CODEDIR/jdk/src/hotspot
  rm -r `find hotspot/ -type f -not -name *.java`
  popd
  ```

- Do the following to work around bugs in Joern:
  - Shorten the huge string literals in `jdk/src/jdk.charsets/share/classes/sun/nio/cs/ext/IBM33722.java`
  - Add this line to L48 of`jdk_j_to_z/jdk.jconsole/share/classes/sun/tools/jconsole/inspector/TableSorter.java`

    ```
    private Vector<Object> dataVector;
    ```

  - Change line  667 and 1079 in `jdk/src/jdk.compiler/share/classes/com/sun/tools/javac/comp/DeferredAttr.java` by removing
    the spaces in `.new Thing`. Example for L667:

    ```
    InferenceGraph graph = infer.new GraphSolver(inferenceContext, types.noWarnings)
            .new InferenceGraph();
    ```

    to this

    ```
    InferenceGraph graph = infer.newGraphSolver(inferenceContext, types.noWarnings)
            .newInferenceGraph();
    ```

## Extracting Inline Comments
The following will extract inline comments and its surrounding lines of code (1
before, 5 after) for any (sub)project into a JSON representation. Note that
Joern's Java parser can handle only about 1200 files, so it is best to
parse subprojects or smaller subdivisions thereof.

```
CODEDIR=/tmp/codebases
OUTDIR=/tmp/proc
mkdir $OUTDIR
cd joern
./joern --script ../extraction_scripts/ast_cpg_to_comment_stats.sc \
     --params name=hadoop-common-project,out=$OUTDIR/output.json,importDir=$CODEDIR/hadoop/
```

Here is how we processed the five codebases:
- Hadoop: Process this one sub-project at a time. Larger subprojects may need
  to be split into two or three passes.
- Spring: Process this by sub-project. Alternatively, subdivide the project
  into 5 roughly equal subdivisions. This worked for us but needs to be tracked
  carefully.
- Guava: Per-package processing, or 2 project subdivisions.
- JUnit: Per-package processing, or 2 project subdivisions.
- JDK: Ditto, or 10 subdivisions.


## Troubleshooting
- **Clear the cache.** Once Joern processes a project, it retains state under
  `workspace`, so it may not pick up changes to the source code. To fix this,
  delete the relevant project's directory under `workspace`.

- **Diffgraph errors.** These do not appear to affect the output, so they can
  be ignored.

- **Careful with delete-based optimizations.** One might think that deleting
  all Java files without inline comments will speed up processing times, but
  this may lead to errors like the following import issue, or others we haven't
  seen yet. (At least we're not parsing C++ - that parser includes all the
  standard libraries, which are bundled into the output. This also means that
  C++ processing times are slower, and fewer source files can be parsed
  compared to Java.)

- **Dependency graph problems.** The following error may be due to importing a
  package that depends on a type outside the package.

  ```
  java.lang.StackOverflowError
        at java.base/java.util.regex.Pattern$BmpCharProperty.match(Pattern.java:3963)
        // Many more regex calls.
        at java.base/java.lang.String.format(String.java:2897)
        at com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserTypeParameter.getQualifiedName(JavaParserTypeParameter.java:150)
        at com.github.javaparser.resolution.types.parametrization.ResolvedTypeParametersMap.getValue(ResolvedTypeParametersMap.java:107)
        at com.github.javaparser.resolution.types.ResolvedReferenceType.lambda$typeParametersValues$5(ResolvedReferenceType.java:282)
        at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:195)
        // More java.util.stream stuff.
        at com.github.javaparser.resolution.types.ResolvedReferenceType.typeParametersValues(ResolvedReferenceType.java:282)
        at com.github.javaparser.symbolsolver.model.typesystem.ReferenceTypeImpl.transformTypeParameters(ReferenceTypeImpl.java:195)
        at com.github.javaparser.resolution.types.parametrization.ResolvedTypeParameterValueProvider.useThisTypeParametersOnTheGivenType(ResolvedTypeParameterValueProvider.java:71)
        at com.github.javaparser.symbolsolver.model.typesystem.ReferenceTypeImpl.transformTypeParameters(ReferenceTypeImpl.java:196)
        at com.github.javaparser.resolution.types.parametrization.ResolvedTypeParameterValueProvider.useThisTypeParametersOnTheGivenType(ResolvedTypeParameterValueProvider.java:71)
        // Repeats forever.
  ```

  - **How to fix:** Address this by considering how packages are subdivided, or remove the
    import and add an empty file in the same directory.


## Appendix: How Joern parses comments
- End-of-line comments are considered as appearing before the line. For example:

  ```
  foo.bar();  // This is a comment
  ```

  Is equivalent to

  ```
  // This is a comment
  foo.bar();

