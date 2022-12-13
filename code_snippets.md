# Code Snippets

These are the code snippets used in our study.

#### Code Snippet 1

Inline comment being studied:  The line that starts with `// Considering that ...`

```
@Override
public FileStatus getFileStatus(Path f) throws IOException {
  Path absolutePath = makeAbsolute(f);
  String key = pathToKey(absolutePath);

  if (key.length() == 0) {
    // root always exists
    return newDirectory(absolutePath);
  }

  LOG.debug("Call the getFileStatus to obtain the metadata for "
      + "the file: [{}].", f);

  FileMetadata meta = store.retrieveMetadata(key);
  if (meta != null) {
    if (meta.isFile()) {
      LOG.debug("Path: [{}] is a file. COS key: [{}]", f, key);
      return newFile(meta, absolutePath);
    } else {
      LOG.debug("Path: [{}] is a dir. COS key: [{}]", f, key);
      return newDirectory(meta, absolutePath);
    }
  }

  if (!key.endsWith(PATH_DELIMITER)) {
    key += PATH_DELIMITER;
  }

  // Considering that the object store's directory is a common prefix in
  // the object key, it needs to check the existence of the path by listing
  // the COS key.
  LOG.debug("List COS key: [{}] to check the existence of the path.", key);
  PartialListing listing = store.list(key, 1);
  if (listing.getFiles().length > 0
      || listing.getCommonPrefixes().length > 0) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Path: [{}] is a directory. COS key: [{}]", f, key);
    }
    return newDirectory(absolutePath);
  }

  throw new FileNotFoundException(
      "No such file or directory '" + absolutePath + "'");
}
```

#### Code Snippet 2

Inline comment being studied: The line that starts with `// Fetch the ...`

```
@Override
public KeyVersion decryptEncryptedKey(
    EncryptedKeyVersion encryptedKeyVersion)
    throws IOException, GeneralSecurityException {
  // Fetch the encryption key material
  final String encryptionKeyVersionName =
      encryptedKeyVersion.getEncryptionKeyVersionName();
  final KeyVersion encryptionKey =
      keyProvider.getKeyVersion(encryptionKeyVersionName);
  Preconditions
      .checkNotNull(encryptionKey, "KeyVersion name '%s' does not exist",
          encryptionKeyVersionName);
  Preconditions.checkArgument(
      encryptedKeyVersion.getEncryptedKeyVersion().getVersionName()
          .equals(KeyProviderCryptoExtension.EEK),
      "encryptedKey version name must be '%s', but found '%s'",
      KeyProviderCryptoExtension.EEK,
      encryptedKeyVersion.getEncryptedKeyVersion().getVersionName());

  try (CryptoCodec cc = CryptoCodec.getInstance(keyProvider.getConf())) {
    final Decryptor decryptor = cc.createDecryptor();
    return decryptEncryptedKey(decryptor, encryptionKey,
        encryptedKeyVersion);
  }
}
```

#### Code Snippet 3

Inline comment being studied: The line that starts with `// Safe because ...`

```
@Override
@ParametricNullness
public final T next() {
  if (!hasNext()) {
    throw new NoSuchElementException();
  }
  state = State.NOT_READY;
  // Safe because hasNext() ensures that tryToComputeNext() has put a T into `next`.
  T result = uncheckedCastNullableTToT(next);
  next = null;
  return result;
}
```

#### Code Snippet 4

Inline comment being studied: The line that starts with `// we have work ... `

```
/**
 * Match the given list of extracted variable names to argument slots.
 */
private void bindAnnotationsFromVarNames(List<String> varNames) {
  if (!varNames.isEmpty()) {
    // we have work to do...
    int numAnnotationSlots = countNumberOfUnboundAnnotationArguments();
    if (numAnnotationSlots > 1) {
      throw new AmbiguousBindingException("Found " + varNames.size() +
          " potential annotation variable(s), and " +
          numAnnotationSlots + " potential argument slots");
    }
    else if (numAnnotationSlots == 1) {
      if (varNames.size() == 1) {
        // it's a match
        findAndBind(Annotation.class, varNames.get(0));
      }
      else {
        // multiple candidate vars, but only one slot
        throw new IllegalArgumentException("Found " + varNames.size() +
            " candidate annotation binding variables" +
            " but only one potential argument binding slot");
      }
    }
    else {
      // no slots so presume those candidate vars were actually type names
    }
  }
}
```

#### Code Snippet 5

Inline comment being studied: The line that starts with `// if it's a class ...`

```
private static String formatClassAndValue(Object value, String valueString) {
  // If the value is null, return <null> instead of null<null>.
  if (value == null) {
    return "<null>";
  }
  String classAndHash = getClassName(value) + toHash(value);
  // if it's a class, there's no need to repeat the class name contained in the valueString.
  return (value instanceof Class ? "<" + classAndHash + ">" : classAndHash + "<" + valueString + ">");
}
```

#### Code Snippet 6

Inline comment being studied: The line that starts with `// Try each factory ...`

```
/**
 * Creates an object using the factories specified in the
 * {@code Context.OBJECT_FACTORIES} property of the environment
 * or of the provider resource file associated with {@code nameCtx}.
 *
 * @return factory created; null if cannot create
 */
private static Object createObjectFromFactories(Object obj, Name name,
        Context nameCtx, Hashtable<?,?> environment) throws Exception {

    FactoryEnumeration factories = ResourceManager.getFactories(
        Context.OBJECT_FACTORIES, environment, nameCtx);

    if (factories == null)
        return null;

    // Try each factory until one succeeds
    ObjectFactory factory;
    Object answer = null;
    while (answer == null && factories.hasMore()) {
        factory = (ObjectFactory)factories.next();
        answer = factory.getObjectInstance(obj, name, nameCtx, environment);
    }
    return answer;
}
```
