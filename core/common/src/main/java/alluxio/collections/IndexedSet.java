/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.collections;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.concurrent.ThreadSafe;

/**
 * A set of objects that are indexed and thus can be queried by specific fields of the object.
 * Different {@link IndexedSet} instances may specify different fields to index. The field type must
 * be comparable. The field value must not be changed after an object is added to the set,
 * otherwise, behavior for all operations is not specified.
 *
 * If concurrent adds or removes for objects which are equivalent, but not the same exact object,
 * the behavior is undefined. Therefore, do not add or remove "clones" objects in the
 * {@link IndexedSet}.
 *
 * <p>
 * Example usage:
 *
 * We have a set of puppies:
 *
 * <pre>
 * class Puppy {
 *   private final String mName;
 *   private final long mId;
 *
 *   public Puppy(String name, long id) {
 *     mName = name;
 *     mId = id;
 *   }
 *
 *   public String name() {
 *     return mName;
 *   }
 *
 *   public long id() {
 *     return mId;
 *   }
 * }
 * </pre>
 *
 * We want to be able to retrieve the set of puppies via a puppy's id or name, one way is to have
 * two maps like {@code Map<String, Puppy> nameToPuppy} and {@code Map<Long, Puppy> idToPuppy},
 * another way is to use a single instance of {@link IndexedSet}!
 *
 * First, define the fields to be indexed:
 *
 * <pre>
 *  FieldIndex<Puppy> idIndex = new FieldIndex<Puppy> {
 *    {@literal @Override}
 *    Object getFieldValue(Puppy o) {
 *      return o.id();
 *    }
 *  }
 *
 *  FieldIndex<Puppy> nameIndex = new FieldIndex<Puppy> {
 *    {@literal @Override}
 *    Object getFieldValue(Puppy o) {
 *      return o.name();
 *    }
 *  }
 * </pre>
 *
 * Then create an {@link IndexedSet} and add puppies:
 *
 * <pre>
 * IndexedSet<Puppy> puppies = new IndexedSet<Puppy>(idIndex, nameIndex);
 * puppies.add(new Puppy("sweet", 0));
 * puppies.add(new Puppy("heart", 1));
 * </pre>
 *
 * Then retrieve the puppy named sweet:
 *
 * <pre>
 * Puppy sweet = puppies.getFirstByField(nameIndex, "sweet");
 * </pre>
 *
 * and retrieve the puppy with id 1:
 *
 * <pre>
 * Puppy heart = puppies.getFirstByField(idIndex, 1L);
 * </pre>
 *
 * @param <T> the type of object
 */
@ThreadSafe
public class IndexedSet<T> extends AbstractSet<T> {
  /** All objects in the set. This set is required to guarantee uniqueness of objects. */
  // TODO(gpang): remove this set, and just use the indexes.
  private final ConcurrentHashSet<T> mObjects = new ConcurrentHashSet<>(8, 0.95f, 8);
  /**
   * Map from {@link FieldIndex} to the index. An index is a map from index value to one or a set of
   * objects with that index value. A unique index is an index where each index value only maps to
   * one object. A non-unique index is an index where an index value can map to one or more objects.
   */
  private final Map<UniqueFieldIndex<T>, ConcurrentHashMap<Object, T>> mIndexMapUnique;
  private final Map<NonUniqueFieldIndex<T>, ConcurrentHashMap<Object, ConcurrentHashSet<T>>>
      mIndexMapNonUnique;

  /**
   * An interface representing an index for this {@link IndexedSet}, each index for this set must
   * implement the interface to define how to get the value of the field chosen as the index. Users
   * must use the same instance of the implementation of this interface as the parameter in all
   * methods of {@link IndexedSet} to represent the same index.
   *
   * @param <T> type of objects in this {@link IndexedSet}
   */
  public interface FieldIndex<T> {
    /**
     * Gets the value of the field that serves as index.
     *
     * @param o the instance to get the field value from
     * @return the field value, which is just an Object
     */
    Object getFieldValue(T o);
  }

  /**
   * An interface extending {@link FieldIndex}, represents a unique index. A unique index is an
   * index where each index value only maps to one object.
   *
   * @param <T> type of objects in this {@link IndexedSet}
   */
  public interface UniqueFieldIndex<T> extends FieldIndex<T> {
  }

  /**
   * An interface extending {@link FieldIndex}, represents a non-unique index. A non-unique index is
   * an index where a index value can map to one or more objects.
   *
   * @param <T> type of objects in this {@link IndexedSet}
   */
  public interface NonUniqueFieldIndex<T> extends FieldIndex<T> {
  }

  /**
   * Constructs a new {@link IndexedSet} instance with at least one field as the index.
   *
   * @param field at least one field is needed to index the set of objects
   * @param otherFields other fields to index the set
   */
  @SafeVarargs
  public IndexedSet(FieldIndex<T> field, FieldIndex<T>... otherFields) {
    // count the numbers of two index types
    int uniqueIndexLength = 0;
    int nonUniqueIndexLength = 0;

    Iterable<FieldIndex<T>> fields =
        Iterables.concat(Arrays.asList(field), Arrays.asList(otherFields));

    for (FieldIndex<T> fieldIndex : fields) {
      if (fieldIndex instanceof UniqueFieldIndex) {
        uniqueIndexLength++;
      } else {
        nonUniqueIndexLength++;
      }
    }

    // initialization
    Map<NonUniqueFieldIndex<T>, ConcurrentHashMap<Object, ConcurrentHashSet<T>>> indexMapNonUnique =
        null;
    Map<UniqueFieldIndex<T>, ConcurrentHashMap<Object, T>> indexMapUnique = null;

    if (uniqueIndexLength > 0) {
      indexMapUnique = new HashMap<>(uniqueIndexLength);
    }
    if (nonUniqueIndexLength > 0) {
      indexMapNonUnique = new HashMap<>(nonUniqueIndexLength);
    }

    for (FieldIndex<T> fieldIndex : fields) {
      if (fieldIndex instanceof UniqueFieldIndex) {
        indexMapUnique.put(
            (UniqueFieldIndex<T>) fieldIndex, new ConcurrentHashMap<Object, T>(8, 0.95f, 8));
      } else {
        indexMapNonUnique.put(
            (NonUniqueFieldIndex<T>) fieldIndex,
            new ConcurrentHashMap<Object, ConcurrentHashSet<T>>(8, 0.95f, 8));
      }
    }

    // read only, so it is thread safe and allows concurrent access.
    if (uniqueIndexLength > 0) {
      mIndexMapUnique = Collections.unmodifiableMap(indexMapUnique);
    } else {
      mIndexMapUnique = null;
    }

    if (nonUniqueIndexLength > 0) {
      mIndexMapNonUnique = Collections.unmodifiableMap(indexMapNonUnique);
    } else {
      mIndexMapNonUnique = null;
    }

  }

  /**
   * Removes all the entries in this set.
   *
   * This is an expensive operation, and concurrent adds are permitted.
   */
  public void clear() {
    for (T obj : mObjects) {
      remove(obj);
    }
  }

  /**
   * Adds an object o to the set if there is no other object o2 such that
   * {@code (o == null ? o2 == null : o.equals(o2))}. If this set already contains the object, the
   * call leaves the set unchanged.
   *
   * @param object the object to add
   * @return true if this set did not already contain the specified element
   */
  @Override
  public boolean add(T object) {
    Preconditions.checkNotNull(object);

    // Locking this object protects against removing the exact object, but does not protect against
    // removing a distinct, but equivalent object.
    synchronized (object) {
      if (!mObjects.addIfAbsent(object)) {
        // This object is already added, possibly by another concurrent thread.
        return false;
      }

      if (mIndexMapUnique != null) {
        for (Map.Entry<UniqueFieldIndex<T>, ConcurrentHashMap<Object, T>> fieldInfo
            : mIndexMapUnique.entrySet()) {
          // For this field, retrieve the value to index
          Object fieldValue = fieldInfo.getKey().getFieldValue(object);
          // Get the unique index for this field
          ConcurrentHashMap<Object, T> index = fieldInfo.getValue();
          // Update the indexes.
          if (index.putIfAbsent(fieldValue, object) != null) {
            throw new IllegalStateException("Adding more than one value to a unique index.");
          }
        }
      }

      if (mIndexMapNonUnique != null) {
        for (Map.Entry<NonUniqueFieldIndex<T>, ConcurrentHashMap<Object, ConcurrentHashSet<T>>>
                 fieldInfo : mIndexMapNonUnique.entrySet()) {
          // For this field, retrieve the value to index
          Object fieldValue = fieldInfo.getKey().getFieldValue(object);
          // Get the non-unique index for this field
          ConcurrentHashMap<Object, ConcurrentHashSet<T>> index = fieldInfo.getValue();
          ConcurrentHashSet<T> objSet = index.get(fieldValue);
          // Update the indexes.
          if (objSet == null) {
            index.putIfAbsent(fieldValue, new ConcurrentHashSet<T>());
            objSet = index.get(fieldValue);
          }
          if (!objSet.addIfAbsent(object)) {
            // this call can never return false because:
            // a. the second-level sets in the indices are all
            // {@link java.util.Set} instances of unbounded space
            // b. We have already successfully added object on mObjects,
            // meaning that it cannot be already in any of the sets.
            // (mObjects is exactly the set-union of all the other second-level sets)
            throw new IllegalStateException("Indexed Set is in an illegal state");
          }
        }
      }
    }
    return true;
  }

  /**
   * Returns an iterator over the elements in this set. The elements are returned in no particular
   * order. It is to implement {@link Iterable} so that users can foreach the {@link IndexedSet}
   * directly.
   *
   * Note that the behaviour of the iterator is unspecified if the underlying collection is modified
   * while a thread is going through the iterator.
   *
   * @return an iterator over the elements in this {@link IndexedSet}
   */
  @Override
  public Iterator<T> iterator() {
    return new IndexedSetIterator();
  }

  /**
   * Specialized iterator for {@link IndexedSet}.
   *
   * This is needed to support consistent removal from the set and the indices.
   */
  private class IndexedSetIterator implements Iterator<T> {
    private final Iterator<T> mSetIterator;
    private T mObject;

    public IndexedSetIterator() {
      mSetIterator = mObjects.iterator();
      mObject = null;
    }

    @Override
    public boolean hasNext() {
      return mSetIterator.hasNext();
    }

    @Override
    public T next() {
      final T next = mSetIterator.next();
      mObject = next;
      return next;
    }

    @Override
    public void remove() {
      if (mObject != null) {
        IndexedSet.this.remove(mObject);
        mObject = null;
      } else {
        throw new IllegalStateException("next() was not called before remove()");
      }
    }
  }

  /**
   * Whether there is an object with the specified unique index field value in the set.
   *
   * @param index the field index
   * @param value the field value
   * @return true if there is one such object, otherwise false
   */
  public boolean contains(UniqueFieldIndex<T> index, Object value) {
    T res = getByFieldInternalUnique(index, value);
    return res != null;
  }

  /**
   * Whether there is an object with the specified non-unique field value in the set.
   *
   * @param index the field index
   * @param value the field value
   * @return true if there is one such object, otherwise false
   */
  public boolean contains(NonUniqueFieldIndex<T> index, Object value) {
    ConcurrentHashSet<T> set = getByFieldInternalNonUnique(index, value);
    return set != null && !set.isEmpty();
  }

  /**
   * Gets a subset of objects with the specified unique field value. If there is no object with the
   * specified field value, a newly created empty set is returned. Otherwise, the returned set is
   * backed up by an internal set, so changes in internal set will be reflected in returned set.
   *
   * @param index the field index
   * @param value the field value to be satisfied
   * @return the set of objects or an empty set if no such object exists
   */
  public Set<T> getByField(UniqueFieldIndex<T> index, Object value) {
    Set<T> set = new HashSet<T>();
    T res = getByFieldInternalUnique(index, value);
    if (res != null) {
      set.add(res);
    }
    return set == null ? new HashSet<T>() : Collections.unmodifiableSet(set);
  }

  /**
   * Gets a subset of objects with the specified non-unique field value. If there is no object with
   * the specified field value, a newly created empty set is returned. Otherwise, the returned set
   * is backed up by an internal set, so changes in internal set will be reflected in returned set.
   *
   * @param index the field index
   * @param value the field value to be satisfied
   * @return the set of objects or an empty set if no such object exists
   */
  public Set<T> getByField(NonUniqueFieldIndex<T> index, Object value) {
    Set<T> set = getByFieldInternalNonUnique(index, value);
    return set == null ? new HashSet<T>() : Collections.unmodifiableSet(set);
  }

  /**
   * Gets the object from the set of objects with the specified unique field value.
   *
   * @param index the field index
   * @param value the field value
   * @return the object or null if there is no such object
   */
  public T getFirstByField(UniqueFieldIndex<T> index, Object value) {
    T res = getByFieldInternalUnique(index, value);
    return res;
  }

  /**
   * Gets the object from the set of objects with the specified non-unique field value.
   *
   * @param index the field index
   * @param value the field value
   * @return the object or null if there is no such object
   */
  public T getFirstByField(NonUniqueFieldIndex<T> index, Object value) {
    Set<T> all = getByFieldInternalNonUnique(index, value);
    try {
      return all == null || !all.iterator().hasNext() ? null : all.iterator().next();
    } catch (NoSuchElementException e) {
      return null;
    }
  }

  /**
   * Removes an object from the set.
   *
   * @param object the object to remove
   * @return true if the object is in the set and removed successfully, otherwise false
   */
  @Override
  public boolean remove(Object object) {
    // Locking this object protects against removing the exact object that might be in the
    // process of being added, but does not protect against removing a distinct, but equivalent
    // object.
    synchronized (object) {
      if (mObjects.contains(object)) {
        // This isn't technically typesafe. However, given that success is true, it's very unlikely
        // that the object passed to remove is not of type <T>.
        @SuppressWarnings("unchecked")
        T tObj = (T) object;
        removeFromIndices(tObj);
        return mObjects.remove(tObj);
      } else {
        return false;
      }
    }
  }

  /**
   * Helper method that removes an object from the indices.
   *
   * @param object the object to be removed
   */
  private void removeFromIndices(T object) {
    if (mIndexMapUnique != null) {
      for (Map.Entry<UniqueFieldIndex<T>, ConcurrentHashMap<Object, T>> fieldInfo :
          mIndexMapUnique.entrySet()) {
        Object fieldValue = fieldInfo.getKey().getFieldValue(object);
        ConcurrentHashMap<Object, T> index = fieldInfo.getValue();

        index.remove(fieldValue, object);
      }
    }

    if (mIndexMapNonUnique != null) {
      for (Map.Entry<NonUniqueFieldIndex<T>, ConcurrentHashMap<Object, ConcurrentHashSet<T>>>
               fieldInfo : mIndexMapNonUnique.entrySet()) {
        Object fieldValue = fieldInfo.getKey().getFieldValue(object);
        ConcurrentHashMap<Object, ConcurrentHashSet<T>> index = fieldInfo.getValue();
        ConcurrentHashSet<T> objSet = index.get(fieldValue);
        if (objSet != null) {
          objSet.remove(object);
        }
      }
    }
  }

  /**
   * Removes the object with the specified unique index field value.
   *
   * @param index the field index
   * @param value the field value
   * @return the number of objects removed
   */
  public int removeByField(UniqueFieldIndex<T> index, Object value) {
    int removed = 0;
    T toRemove = getByFieldInternalUnique(index, value);

    if (toRemove == null) {
      return 0;
    }
    if (remove(toRemove)) {
      removed++;
    }

    return removed;
  }

  /**
   * Removes the subset of objects with the specified non-unique index field value.
   *
   * @param index the field index
   * @param value the field value
   * @return the number of objects removed
   */
  public int removeByField(NonUniqueFieldIndex<T> index, Object value) {
    int removed = 0;
    ConcurrentHashSet<T> toRemove = getByFieldInternalNonUnique(index, value);

    if (toRemove == null) {
      return 0;
    }
    for (T o : toRemove) {
      if (remove(o)) {
        removed++;
      }
    }

    return removed;
  }

  /**
   * @return the number of objects in this indexed set (O(1) time)
   */
  @Override
  public int size() {
    return mObjects.size();
  }

  /**
   * Gets the set of objects with the specified field value - internal function.
   *
   * @param index the field index
   * @param value the field value
   * @return the set of objects with the specified field value
   */
  private T getByFieldInternalUnique(UniqueFieldIndex<T> index, Object value) {
    if (mIndexMapUnique == null) {
      return null;
    }
    return mIndexMapUnique.get(index).get(value);
  }

  private ConcurrentHashSet<T> getByFieldInternalNonUnique(NonUniqueFieldIndex<T> index,
      Object value) {
    if (mIndexMapNonUnique == null) {
      return null;
    }
    return mIndexMapNonUnique.get(index).get(value);
  }
}
