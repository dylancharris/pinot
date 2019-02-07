.. _pql:

PQL
===

* PQL is a derivative of SQL derivative that supports selection, projection, aggregation, grouping aggregation.
  There is no support for Joins.

* Specifically, for Pinot:

  * Grouping keys always appear in query results, even if not requested
  *  Aggregations are computed in parallel
  * Results of aggregations with large amounts of group keys (>1M) are approximated
  * ``ORDER BY`` only works for selection queries, for aggregations one must use the ``TOP`` keyword

PQL Examples
------------

The Pinot Query Language (PQL) is very similar to standard SQL:

.. code-block:: sql

  SELECT COUNT(*) FROM myTable

Aggregation
-----------

.. code-block:: sql

  SELECT COUNT(*), MAX(foo), SUM(bar) FROM myTable

Grouping on Aggregation
-----------------------

.. code-block:: sql

  SELECT MIN(foo), MAX(foo), SUM(foo), AVG(foo) FROM myTable
    GROUP BY bar, baz TOP 50

Filtering
---------

.. code-block:: sql

  SELECT COUNT(*) FROM myTable
    WHERE foo = 'foo'
    AND bar BETWEEN 1 AND 20
    OR (baz < 42 AND quux IN ('hello', 'goodbye') AND quuux NOT IN (42, 69))

Selection (Projection)
----------------------

.. code-block:: sql

  SELECT * FROM myTable
    WHERE quux < 5
    LIMIT 50

Ordering on Selection
---------------------

.. code-block:: sql

  SELECT foo, bar FROM myTable
    WHERE baz > 20
    ORDER BY bar DESC
    LIMIT 100

Pagination on Selection
-----------------------
Note: results might not be consistent if column ordered by has same value in multiple rows.

.. code-block:: sql

  SELECT foo, bar FROM myTable
    WHERE baz > 20
    ORDER BY bar DESC
    LIMIT 50, 100

Wild-card match (in WHERE clause only)
--------------------------------------

To count rows where the column ``airlineName`` starts with ``U``

.. code-block:: sql

  SELECT count(*) FROM SomeTable
    WHERE regexp_like(airlineName, '^U.*')
    GROUP BY airlineName TOP 10

Examples with UDF
-----------------

As of now, functions have to be implemented within Pinot. Injecting functions is not allowed yet.
The examples below demonstrate the use of UDFs

.. code-block:: sql

  SELECT count(*) FROM myTable
    GROUP BY timeConvert(timeColumnName, 'SECONDS', 'DAYS')

  SELECT count(*) FROM myTable
    GROUP BY div(tim

PQL Specification
-----------------

SELECT
^^^^^^

The select statement is as follows

.. code-block:: sql

  SELECT <outputColumn> (, outputColumn, outputColumn,...)
    FROM <tableName>
    (WHERE ... | GROUP BY ... | ORDER BY ... | TOP ... | LIMIT ...)

``outputColumn`` can be ``*`` to project all columns, columns (``foo``, ``bar``, ``baz``) or aggregation functions like (``MIN(foo)``, ``MAX(bar)``, ``AVG(baz)``).

Supported aggregations on single-value columns
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* ``COUNT``
* ``MIN``
* ``MAX``
* ``SUM``
* ``AVG``
* ``MINMAXRANGE``
* ``DISTINCTCOUNT``
* ``DISTINCTCOUNTHLL``
* ``FASTHLL``
* ``PERCENTILE[0-100]``: e.g. ``PERCENTILE5``, ``PERCENTILE50``, ``PERCENTILE99``, etc.
* ``PERCENTILEEST[0-100]``: e.g. ``PERCENTILEEST5``, ``PERCENTILEEST50``, ``PERCENTILEEST99``, etc.

Supported aggregations on multi-value columns
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* ``COUNTMV``
* ``MINMV``
* ``MAXMV``
* ``SUMMV``
* ``AVGMV``
* ``MINMAXRANGEMV``
* ``DISTINCTCOUNTMV``
* ``DISTINCTCOUNTHLLMV``
* ``FASTHLLMV``
* ``PERCENTILE[0-100]MV``: e.g. ``PERCENTILE5MV``, ``PERCENTILE50MV``, ``PERCENTILE99MV``, etc.
* ``PERCENTILEEST[0-100]MV``: e.g. ``PERCENTILEEST5MV``, ``PERCENTILEEST50MV``, ``PERCENTILEEST99MV``, etc.

WHERE
^^^^^

Supported predicates are comparisons with a constant using the standard SQL operators (``=``, ``<``, ``<=``, ``>``, ``>=``, ``<>``, '!=') , range comparisons using ``BETWEEN`` (``foo BETWEEN 42 AND 69``), set membership (``foo IN (1, 2, 4, 8)``) and exclusion (``foo NOT IN (1, 2, 4, 8)``). For ``BETWEEN``, the range is inclusive.

Comparison with a regular expression is supported using the regexp_like function, as in ``WHERE regexp_like(columnName, 'regular expression')``

GROUP BY
^^^^^^^^

The ``GROUP BY`` clause groups aggregation results by a list of columns, or transform functions on columns (see below)


ORDER BY
^^^^^^^^

The ``ORDER BY`` clause orders selection results by a list of columns. PQL supports ordering ``DESC`` or ``ASC``.

TOP
^^^

The ``TOP n`` clause causes the 'n' largest group results to be returned. If not specified, the top 10 groups are returned.

LIMIT
^^^^^

The ``LIMIT n`` clause causes the selection results to contain at most 'n' results.
The ``LIMIT a, b`` clause paginate the selection results from the 'a' th results and return at most 'b' results.

Transform Function in Aggregation and Grouping
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In aggregation and grouping, each column can be transformed from one or multiple columns.
For example, the following query will calculate the maximum value of column ``foo`` divided by column ``bar`` grouping on the column ``time`` converted form time unit ``MILLISECONDS`` to ``SECONDS``:

.. code-block:: sql

  SELECT MAX(DIV(foo, bar) FROM myTable
    GROUP BY TIMECONVERT(time, 'MILLISECONDS', 'SECONDS')

Supported transform functions
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
``ADD``
   Sum of at least two values

``SUB``
   Difference between two values

``MULT``
   Product of at least two values

``DIV``
   Quotient of two values

``TIMECONVERT``
   Takes 3 arguments, converts the value into another time unit. E.g. ``TIMECONVERT(time, 'MILLISECONDS', 'SECONDS')``

``DATETIMECONVERT``
   Takes 4 arguments, converts the value into another date time format, and buckets time based on the given time granularity.
   *e.g.* ``DATETIMECONVERT(date, '1:MILLISECONDS:EPOCH', '1:SECONDS:EPOCH', '15:MINUTES')``

``VALUEIN``
   Takes at least 2 arguments, where the first argument is a multi-valued column, and the following arguments are constant values.
   The transform function will filter the value from the multi-valued column with the given constant values.
   The ``VALUEIN`` transform function is especially useful when the same multi-valued column is both filtering column and grouping column.
   *e.g.* ``VALUEIN(mvColumn, 3, 5, 15)``


Differences with SQL
--------------------

* ``JOIN`` is not supported
* Use ``TOP`` instead of ``LIMIT`` for truncation
* ``LIMIT n`` has no effect in grouping queries, should use ``TOP n`` instead. If no ``TOP n`` defined, PQL will use ``TOP 10`` as default truncation setting.
* No need to select the columns to group with.

The following two queries are both supported in PQL, where the non-aggregation columns are ignored.

.. code-block:: sql

  SELECT MIN(foo), MAX(foo), SUM(foo), AVG(foo) FROM mytable
    GROUP BY bar, baz
    TOP 50

  SELECT bar, baz, MIN(foo), MAX(foo), SUM(foo), AVG(foo) FROM mytable
    GROUP BY bar, baz
    TOP 50

* The results will always order by the aggregated value (descending).

The results for query:

.. code-block:: sql

  SELECT MIN(foo), MAX(foo) FROM myTable
    GROUP BY bar
    TOP 50

will be the same as the combining results from the following queries:

.. code-block:: sql

  SELECT MIN(foo) FROM myTable
    GROUP BY bar
    TOP 50
  SELECT MAX(foo) FROM myTable
    GROUP BY bar
    TOP 50

where we don't put the results for the same group together.
