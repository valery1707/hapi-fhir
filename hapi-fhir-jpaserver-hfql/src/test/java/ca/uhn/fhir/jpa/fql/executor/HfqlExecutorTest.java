package ca.uhn.fhir.jpa.fql.executor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.fql.parser.HfqlStatement;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.ParamPrefixEnum;
import ca.uhn.fhir.rest.param.QuantityParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IPagingProvider;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.util.FhirContextSearchParamRegistry;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import com.google.common.collect.Lists;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.annotation.Nonnull;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class HfqlExecutorTest {

	private final RequestDetails mySrd = new SystemRequestDetails();
	@Spy
	private FhirContext myCtx = FhirContext.forR4Cached();
	@Mock
	private DaoRegistry myDaoRegistry;
	@Mock
	private IPagingProvider myPagingProvider;
	@Spy
	private ISearchParamRegistry mySearchParamRegistry = new FhirContextSearchParamRegistry(myCtx);
	@InjectMocks
	private HfqlExecutor myHfqlExecutor = new HfqlExecutor();
	@Captor
	private ArgumentCaptor<SearchParameterMap> mySearchParameterMapCaptor;

	@Test
	public void testContinuation() {
		// Setup
		HfqlStatement statement = new HfqlStatement();
		statement.setFromResourceName("Patient");
		statement.addSelectClause("name.given[1]");
		statement.addSelectClause("name.family");
		statement.addWhereClause("name.family = 'Simpson'", HfqlStatement.WhereClauseOperatorEnum.UNARY_BOOLEAN);

		String searchId = "the-search-id";
		when(myPagingProvider.retrieveResultList(any(), eq(searchId))).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		// Test
		IHfqlExecutionResult result = myHfqlExecutor.executeContinuation(statement, searchId, 3, 100, mySrd);

		// Verify
		assertThat(result.getColumnNames(), contains(
			"name.given[1]", "name.family"
		));
		assertTrue(result.hasNext());
		IHfqlExecutionResult.Row nextRow = result.getNextRow();
		assertEquals(3, nextRow.getRowOffset());
		assertThat(nextRow.getRowValues(), contains("Marie", "Simpson"));
		assertTrue(result.hasNext());
		nextRow = result.getNextRow();
		assertEquals(4, nextRow.getRowOffset());
		assertThat(nextRow.getRowValues(), contains("Evelyn", "Simpson"));
		assertFalse(result.hasNext());

	}


	@Test
	public void testFromSelect() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					where name.family = 'Simpson'
					select name.given[1], name.family
			""";

		IHfqlExecutionResult.Row nextRow;
		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames(), contains(
			"name.given[1]", "name.family"
		));
		assertTrue(result.hasNext());
		nextRow = result.getNextRow();
		assertEquals(0, nextRow.getRowOffset());
		assertThat(nextRow.getRowValues(), contains("Jay", "Simpson"));
		assertTrue(result.hasNext());
		nextRow = result.getNextRow();
		assertEquals(2, nextRow.getRowOffset());
		assertThat(nextRow.getRowValues(), contains("El Barto", "Simpson"));
		assertTrue(result.hasNext());

		verify(patientDao, times(1)).search(mySearchParameterMapCaptor.capture(), any());
		// Default count
		assertNull(mySearchParameterMapCaptor.getValue().getCount());
	}

	@Test
	public void testFromSelectStar() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					where name.family = 'Simpson'
					select *
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"active", "address.city", "address.country"
		));
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), not(hasItem(
			"address.period.start"
		)));
	}

	@Test
	public void testFromSelectCount() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlandersWithSomeDuplicates());
		String statement = """
					from Patient
					select name.family, name.given, count(*)
					group by name.family, name.given
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"name.family", "name.given", "count(*)"
		));
		assertThat(result.getColumnTypes().toString(), result.getColumnTypes(), hasItems(
			HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.LONGINT
		));

		List<List<Object>> rowValues = new ArrayList<>();
		while (result.hasNext()) {
			rowValues.add(new ArrayList<>(result.getNextRow().getRowValues()));
		}
		assertThat(rowValues.toString(), rowValues, containsInAnyOrder(
			Lists.newArrayList("Flanders", "Ned", 2L),
			Lists.newArrayList("Simpson", "Jay", 2L),
			Lists.newArrayList("Simpson", "Marie", 1L),
			Lists.newArrayList("Simpson", "Evelyn", 1L),
			Lists.newArrayList("Simpson", "Homer", 2L),
			Lists.newArrayList("Simpson", "Lisa", 1L),
			Lists.newArrayList("Simpson", "Bart", 1L),
			Lists.newArrayList("Simpson", "El Barto", 1L),
			Lists.newArrayList("Simpson", "Maggie", 1L)
		));
	}

	@Test
	public void testFromSelectCountOrderBy() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlandersWithSomeDuplicates());
		String statement = """
					from Patient
					select name.family, name.given, count(*)
					group by name.family, name.given
					order by count(*) desc, name.family asc, name.given asc
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"name.family", "name.given", "count(*)"
		));
		assertThat(result.getColumnTypes().toString(), result.getColumnTypes(), hasItems(
			HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.LONGINT
		));

		List<List<Object>> rowValues = new ArrayList<>();
		while (result.hasNext()) {
			rowValues.add(new ArrayList<>(result.getNextRow().getRowValues()));
		}
		assertThat(rowValues.toString(), rowValues, contains(
			Lists.newArrayList("Flanders", "Ned", 2L),
			Lists.newArrayList("Simpson", "Homer", 2L),
			Lists.newArrayList("Simpson", "Jay", 2L),
			Lists.newArrayList("Simpson", "Bart", 1L),
			Lists.newArrayList("Simpson", "El Barto", 1L),
			Lists.newArrayList("Simpson", "Evelyn", 1L),
			Lists.newArrayList("Simpson", "Lisa", 1L),
			Lists.newArrayList("Simpson", "Maggie", 1L),
			Lists.newArrayList("Simpson", "Marie", 1L)
		));
	}

	@Test
	public void testFromSelectCountOrderBy_WithNulls() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(new SimpleBundleProvider(
			createPatientHomerSimpson(),
			createPatientLisaSimpson(),
			new Patient()
		));
		String statement = """
					from Patient
					select name.family, name.given
					order by name.family desc, name.given desc
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"name.family", "name.given"
		));
		assertThat(result.getColumnTypes().toString(), result.getColumnTypes(), hasItems(
			HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.STRING
		));

		List<List<Object>> rowValues = new ArrayList<>();
		while (result.hasNext()) {
			rowValues.add(new ArrayList<>(result.getNextRow().getRowValues()));
		}
		assertThat(rowValues.toString(), rowValues, contains(
			Lists.newArrayList("Simpson", "Lisa"),
			Lists.newArrayList("Simpson", "Homer"),
			Lists.newArrayList(null, null)
		));
	}

	@Test
	public void testFromSelectCountOrderBy_DateWithNulls() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(new SimpleBundleProvider(
			createPatientHomerSimpson().setBirthDateElement(new DateType("1950-01-01")),
			createPatientLisaSimpson().setBirthDateElement(new DateType("1990-01-01")),
			new Patient()
		));
		String statement = """
					from Patient
					select name.family, name.given, birthDate
					order by birthDate desc
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"name.family", "name.given", "birthDate"
		));
		assertThat(result.getColumnTypes().toString(), result.getColumnTypes(), hasItems(
			HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.DATE
		));

		List<List<Object>> rowValues = new ArrayList<>();
		while (result.hasNext()) {
			rowValues.add(new ArrayList<>(result.getNextRow().getRowValues()));
		}
		assertThat(rowValues.toString(), rowValues, contains(
			Lists.newArrayList("Simpson", "Lisa", "1990-01-01"),
			Lists.newArrayList("Simpson", "Homer", "1950-01-01"),
			Lists.newArrayList(null, null, null)
		));
	}

	@Test
	public void testFromSelectCountOrderBy_BooleanWithNulls() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(new SimpleBundleProvider(
			createPatientHomerSimpson().setActive(true),
			createPatientLisaSimpson().setActive(false),
			createPatientNedFlanders().setActive(true)
		));
		String statement = """
					from Patient
					select name.family, name.given, active
					order by active asc, name.given asc
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"name.family", "name.given", "active"
		));
		assertThat(result.getColumnTypes().toString(), result.getColumnTypes(), hasItems(
			HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.BOOLEAN
		));

		List<List<Object>> rowValues = new ArrayList<>();
		while (result.hasNext()) {
			rowValues.add(new ArrayList<>(result.getNextRow().getRowValues()));
		}
		assertThat(rowValues.toString(), rowValues, contains(
			Lists.newArrayList("Simpson", "Lisa", "false"),
			Lists.newArrayList("Simpson", "Homer", "true"),
			Lists.newArrayList("Flanders", "Ned", "true")
		));
	}

	@Test
	public void testFromSelectCount_NullValues() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);

		when(patientDao.search(any(), any())).thenReturn(createProviderWithSparseNames());

		String statement = """
					from Patient
					select name.family, name.given, count(*), count(name.family)
					group by name.family, name.given
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"name.family", "name.given", "count(*)", "count(name.family)"
		));
		assertThat(result.getColumnTypes().toString(), result.getColumnTypes(), hasItems(
			HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.LONGINT, HfqlDataTypeEnum.LONGINT
		));

		List<List<Object>> rowValues = new ArrayList<>();
		while (result.hasNext()) {
			rowValues.add(new ArrayList<>(result.getNextRow().getRowValues()));
		}
		assertThat(rowValues.toString(), rowValues, containsInAnyOrder(
			Lists.newArrayList(null, "Homer", 1L, 0L),
			Lists.newArrayList("Simpson", "Homer", 1L, 1L),
			Lists.newArrayList("Simpson", null, 1L, 1L),
			Lists.newArrayList(null, null, 1L, 0L)
		));
	}

	@Test
	public void testFromSelectCount_NullValues_NoGroup() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);

		when(patientDao.search(any(), any())).thenReturn(createProviderWithSparseNames());

		String statement = """
					from Patient
					select count(*), count(name.family)
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"count(*)", "count(name.family)"
		));
		assertThat(result.getColumnTypes().toString(), result.getColumnTypes(), hasItems(
			HfqlDataTypeEnum.LONGINT, HfqlDataTypeEnum.LONGINT
		));

		List<List<Object>> rowValues = new ArrayList<>();
		while (result.hasNext()) {
			rowValues.add(new ArrayList<>(result.getNextRow().getRowValues()));
		}
		assertThat(rowValues.toString(), rowValues, containsInAnyOrder(
			Lists.newArrayList(4L, 2L)
		));
	}

	@Test
	public void testFromSelectComplexFhirPath() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					where name.family = 'Simpson'
					select name.given, identifier.where(system = 'http://system' ).value
			""";

		IHfqlExecutionResult.Row nextRow;
		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"name.given", "identifier.where(system = 'http://system' ).value"
		));
		nextRow = result.getNextRow();

		assertEquals("Homer", nextRow.getRowValues().get(0));
		assertEquals("value0", nextRow.getRowValues().get(1));
	}

	@Test
	public void testFromWhereComplexFhirPath() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					where identifier.where(system = 'http://system' ).value = 'value0'
					select name.given, identifier.value
			""";

		IHfqlExecutionResult.Row nextRow;
		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"name.given", "identifier.value"
		));
		nextRow = result.getNextRow();

		assertEquals("Homer", nextRow.getRowValues().get(0));
		assertEquals("value0", nextRow.getRowValues().get(1));
		assertFalse(result.hasNext());
	}

	@Test
	public void testFromWhereComplexFhirPath_StringContains() {
		IFhirResourceDao<Observation> observationDao = initDao(Observation.class);

		Observation obs1 = createCardiologyNoteObservation("Observation/1", "Patient is running a lot");
		Observation obs2 = createCardiologyNoteObservation("Observation/2", "Patient is eating a lot");
		Observation obs3 = createCardiologyNoteObservation("Observation/3", "Patient is running a little");
		Observation obs4 = createCardiologyNoteObservation("Observation/4", "Patient is walking a lot");

		when(observationDao.search(any(), any())).thenReturn(new SimpleBundleProvider(obs1, obs2, obs3, obs4));

		String statement = """
					SELECT id
					FROM Observation
					SEARCH code = 'http://loinc.org|34752-6'
					WHERE
					   value.ofType(string).lower().contains('running')
			""";

		IHfqlExecutionResult.Row nextRow;
		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"id"
		));
		assertThat(result.getColumnTypes().toString(), result.getColumnTypes(), hasItems(
			HfqlDataTypeEnum.STRING
		));

		nextRow = result.getNextRow();
		assertThat(nextRow.getRowValues().toString(), nextRow.getRowValues(), contains(
			"1"
		));
		nextRow = result.getNextRow();
		assertThat(nextRow.getRowValues().toString(), nextRow.getRowValues(), contains(
			"3"
		));
		assertFalse(result.hasNext());
	}

	@Test
	public void testSelectComplexFhirPath_StringConcat() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);

		when(patientDao.search(any(), any())).thenReturn(new SimpleBundleProvider(createPatientHomerSimpson()));

		String statement = """
					SELECT FullName: Patient.name.given + ' ' + Patient.name.family
					FROM Patient
			""";

		IHfqlExecutionResult.Row nextRow;
		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"FullName"
		));
		assertThat(result.getColumnTypes().toString(), result.getColumnTypes(), hasItems(
			HfqlDataTypeEnum.STRING
		));
		nextRow = result.getNextRow();
		assertThat(nextRow.getRowValues().toString(), nextRow.getRowValues(), contains(
			"Homer Simpson"
		));
		assertFalse(result.hasNext());
	}

	@Test
	public void testFromWhereComplexFhirPath_Numeric() {
		IFhirResourceDao<Observation> observationDao = initDao(Observation.class);

		Observation obs1 = createWeightObservationWithKilos("Observation/1", 10L);
		Observation obs2 = createWeightObservationWithKilos("Observation/2", 100L);
		Observation obs3 = createWeightObservationWithKilos("Observation/3", 101L);
		Observation obs4 = createWeightObservationWithKilos("Observation/4", 102L);

		when(observationDao.search(any(), any())).thenReturn(new SimpleBundleProvider(obs1, obs2, obs3, obs4));

		String statement = """
					select
					   id,
					   value.ofType(Quantity).value,
					   value.ofType(Quantity).system,
					   value.ofType(Quantity).code
					from Observation
					where
					   value.ofType(Quantity).value > 100
			""";

		IHfqlExecutionResult.Row nextRow;
		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames().toString(), result.getColumnNames(), hasItems(
			"id", "value.ofType(Quantity).value", "value.ofType(Quantity).system", "value.ofType(Quantity).code"
		));
		assertThat(result.getColumnTypes().toString(), result.getColumnTypes(), hasItems(
			HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.DECIMAL, HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.STRING
		));

		nextRow = result.getNextRow();
		assertThat(nextRow.getRowValues().toString(), nextRow.getRowValues(), contains(
			"3", "101", "http://unitsofmeasure.org", "kg"
		));
	}

	@Test
	public void testFromWhereSelectIn() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					where name.given in ('Foo' | 'Bart')
					select Given:name.given[1], Family:name.family
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames(), contains(
			"Given", "Family"
		));
		assertTrue(result.hasNext());
		IHfqlExecutionResult.Row nextRow = result.getNextRow();
		assertEquals(2, nextRow.getRowOffset());
		assertThat(nextRow.getRowValues(), contains("El Barto", "Simpson"));
		assertFalse(result.hasNext());

	}

	@Test
	public void testFromWhereSelectEquals() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					where name.given = 'Homer'
					select Given:name.given[1], Family:name.family
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertThat(result.getColumnNames(), contains(
			"Given", "Family"
		));
		assertTrue(result.hasNext());
		IHfqlExecutionResult.Row row = result.getNextRow();
		assertEquals(0, row.getRowOffset());
		assertThat(row.getRowValues(), contains("Jay", "Simpson"));
		assertFalse(result.hasNext());

	}

	@Test
	public void testIntrospectTables() {
		IHfqlExecutionResult tables = myHfqlExecutor.introspectTables();
		assertEquals("TABLE_NAME", tables.getColumnNames().get(2));
		assertTrue(tables.hasNext());
		assertEquals("Account", tables.getNextRow().getRowValues().get(2));
	}

	@Test
	public void testIntrospectColumns_NoSelector() {
		IHfqlExecutionResult tables = myHfqlExecutor.introspectColumns(null, null);
		assertEquals("TABLE_NAME", tables.getColumnNames().get(2), tables.getColumnNames().toString());
		assertEquals("COLUMN_NAME", tables.getColumnNames().get(3), tables.getColumnNames().toString());
		assertEquals("DATA_TYPE", tables.getColumnNames().get(4), tables.getColumnNames().toString());
		assertTrue(tables.hasNext());
		assertEquals("Account", tables.getNextRow().getRowValues().get(2));
		assertEquals("description", tables.getNextRow().getRowValues().get(3));
		assertEquals(Types.VARCHAR, tables.getNextRow().getRowValues().get(4));
	}

	@Test
	public void testIntrospectColumns_TableSelector() {
		IHfqlExecutionResult tables = myHfqlExecutor.introspectColumns("Patient", null);
		assertEquals("TABLE_NAME", tables.getColumnNames().get(2), tables.getColumnNames().toString());
		assertEquals("COLUMN_NAME", tables.getColumnNames().get(3), tables.getColumnNames().toString());
		assertEquals("DATA_TYPE", tables.getColumnNames().get(4), tables.getColumnNames().toString());
		assertTrue(tables.hasNext());
		assertEquals("Patient", tables.getNextRow().getRowValues().get(2));
		assertEquals("address.city", tables.getNextRow().getRowValues().get(3));
		assertEquals(Types.VARCHAR, tables.getNextRow().getRowValues().get(4));
	}

	@ValueSource(strings = {
		"_blah", "foo"
	})
	@ParameterizedTest
	public void testSearch_Error_UnknownParam(String theParamName) {
		initDao(Patient.class);

		String statement = "from Patient " +
			"search " + theParamName + " = 'abc' " +
			"select name.given";

		try {
			myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
			fail();
		} catch (InvalidRequestException e) {
			assertEquals("Unknown/unsupported search parameter: " + theParamName, e.getMessage());
		}
	}

	@Test
	public void testSearch_Id_In_CommaList() {
		IFhirResourceDao<Observation> patientDao = initDao(Observation.class);
		Observation resource = new Observation();
		resource.getMeta().setVersionId("5");
		resource.setId("Observation/123");
		resource.setValue(new Quantity(null, 500.1, "http://unitsofmeasure.org", "kg", "kg"));
		when(patientDao.search(any(), any())).thenReturn(new SimpleBundleProvider(resource));

		String statement = """
					select
						id, meta.versionId, value.ofType(Quantity).value
					from
						Observation
					search
						_id in ('123', 'Patient/456')
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);

		assertThat(result.getColumnNames(), contains("id", "meta.versionId", "value.ofType(Quantity).value"));
		assertThat(result.getColumnTypes(), contains(HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.LONGINT, HfqlDataTypeEnum.DECIMAL));
		assertTrue(result.hasNext());
		List<Object> nextRow = result.getNextRow().getRowValues();
		assertEquals("123", nextRow.get(0));
		assertEquals("5", nextRow.get(1));
		assertEquals("500.1", nextRow.get(2));

		verify(patientDao, times(1)).search(mySearchParameterMapCaptor.capture(), any());
		SearchParameterMap map = mySearchParameterMapCaptor.getValue();
		assertEquals(1, map.get("_id").size());
		assertEquals(2, map.get("_id").get(0).size());
		assertNull(((TokenParam) map.get("_id").get(0).get(0)).getSystem());
		assertEquals("123", ((TokenParam) map.get("_id").get(0).get(0)).getValue());
		assertNull(((TokenParam) map.get("_id").get(0).get(1)).getSystem());
		assertEquals("Patient/456", ((TokenParam) map.get("_id").get(0).get(1)).getValue());
	}

	@Test
	public void testSearch_QualifiedSelect() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					select Patient.name.given
			""";

		IHfqlExecutionResult outcome = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);
		assertTrue(outcome.hasNext());
		assertEquals("Homer", outcome.getNextRow().getRowValues().get(0));

	}

	@Test
	public void testSearch_UnknownSelector() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());


		String statement = """
					select
						name.given, foo
					from
						Patient
			""";

		IHfqlExecutionResult result = myHfqlExecutor.executeInitialSearch(statement, null, mySrd);

		assertThat(result.getColumnNames(), contains("name.given", "foo"));
		assertThat(result.getColumnTypes(), contains(HfqlDataTypeEnum.STRING, HfqlDataTypeEnum.STRING));
		assertTrue(result.hasNext());
		List<Object> nextRow = result.getNextRow().getRowValues();
		assertEquals("Homer", nextRow.get(0));
		assertNull(nextRow.get(1));
	}

	@Test
	public void testSearch_LastUpdated_In() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					search _lastUpdated in ('lt2021' | 'gt2023')
					select name.given
			""";

		myHfqlExecutor.executeInitialSearch(statement, null, mySrd);

		verify(patientDao, times(1)).search(mySearchParameterMapCaptor.capture(), any());
		SearchParameterMap map = mySearchParameterMapCaptor.getValue();
		assertEquals(1, map.get("_lastUpdated").size());
		assertEquals(2, map.get("_lastUpdated").get(0).size());
		assertEquals(ParamPrefixEnum.LESSTHAN, ((DateParam) map.get("_lastUpdated").get(0).get(0)).getPrefix());
		assertEquals("2021", ((DateParam) map.get("_lastUpdated").get(0).get(0)).getValueAsString());
		assertEquals(ParamPrefixEnum.GREATERTHAN, ((DateParam) map.get("_lastUpdated").get(0).get(1)).getPrefix());
		assertEquals("2023", ((DateParam) map.get("_lastUpdated").get(0).get(1)).getValueAsString());
	}

	@Test
	public void testSearch_Boolean() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					search active = true
					select name.given
			""";

		myHfqlExecutor.executeInitialSearch(statement, null, mySrd);

		verify(patientDao, times(1)).search(mySearchParameterMapCaptor.capture(), any());
		SearchParameterMap map = mySearchParameterMapCaptor.getValue();
		assertEquals(1, map.get("active").size());
		assertEquals(1, map.get("active").get(0).size());
		assertNull(((TokenParam) map.get("active").get(0).get(0)).getSystem());
		assertEquals("true", ((TokenParam) map.get("active").get(0).get(0)).getValue());
	}

	@Test
	public void testSearch_Quantity() {
		IFhirResourceDao<Observation> observationDao = initDao(Observation.class);
		when(observationDao.search(any(), any())).thenReturn(new SimpleBundleProvider());

		String statement = """
					from Observation
					search value-quantity = 'lt500|http://unitsofmeasure.org|kg'
					select id
			""";

		myHfqlExecutor.executeInitialSearch(statement, null, mySrd);

		verify(observationDao, times(1)).search(mySearchParameterMapCaptor.capture(), any());
		SearchParameterMap map = mySearchParameterMapCaptor.getValue();
		assertEquals(1, map.get("value-quantity").size());
		assertEquals(1, map.get("value-quantity").get(0).size());
		assertEquals("500", ((QuantityParam) map.get("value-quantity").get(0).get(0)).getValue().toString());
		assertEquals(ParamPrefixEnum.LESSTHAN, ((QuantityParam) map.get("value-quantity").get(0).get(0)).getPrefix());
		assertEquals("http://unitsofmeasure.org", ((QuantityParam) map.get("value-quantity").get(0).get(0)).getSystem());
		assertEquals("kg", ((QuantityParam) map.get("value-quantity").get(0).get(0)).getUnits());
	}

	@Test
	public void testSearch_String() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					search name = 'abc'
					select name.given
			""";

		myHfqlExecutor.executeInitialSearch(statement, null, mySrd);

		verify(patientDao, times(1)).search(mySearchParameterMapCaptor.capture(), any());
		SearchParameterMap map = mySearchParameterMapCaptor.getValue();
		assertEquals(1, map.get("name").size());
		assertEquals(1, map.get("name").get(0).size());
		assertEquals("abc", ((StringParam) map.get("name").get(0).get(0)).getValue());
	}

	@Test
	public void testSearch_String_Exact() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					search name:exact = 'abc'
					select name.given
			""";

		myHfqlExecutor.executeInitialSearch(statement, null, mySrd);

		verify(patientDao, times(1)).search(mySearchParameterMapCaptor.capture(), any());
		SearchParameterMap map = mySearchParameterMapCaptor.getValue();
		assertEquals(1, map.get("name").size());
		assertEquals(1, map.get("name").get(0).size());
		assertEquals("abc", ((StringParam) map.get("name").get(0).get(0)).getValue());
		assertTrue(((StringParam) map.get("name").get(0).get(0)).isExact());
	}

	@Test
	public void testSearch_String_AndOr() {
		IFhirResourceDao<Patient> patientDao = initDao(Patient.class);
		when(patientDao.search(any(), any())).thenReturn(createProviderWithSomeSimpsonsAndFlanders());

		String statement = """
					from Patient
					search name in ('A' | 'B') and name in ('C' | 'D')
					select name.given
			""";

		myHfqlExecutor.executeInitialSearch(statement, null, mySrd);

		verify(patientDao, times(1)).search(mySearchParameterMapCaptor.capture(), any());
		SearchParameterMap map = mySearchParameterMapCaptor.getValue();
		assertEquals(2, map.get("name").size());
		assertEquals(2, map.get("name").get(0).size());
		assertEquals("A", ((StringParam) map.get("name").get(0).get(0)).getValue());
		assertEquals("B", ((StringParam) map.get("name").get(0).get(1)).getValue());
		assertEquals("C", ((StringParam) map.get("name").get(1).get(0)).getValue());
		assertEquals("D", ((StringParam) map.get("name").get(1).get(1)).getValue());
	}

	@Test
	public void testError_InvalidFromType() {
		String input = """
			from Foo
			select Foo.blah
			""";

		assertEquals("Invalid FROM statement. Unknown resource type 'Foo' at position: [line=0, column=5]",
			assertThrows(DataFormatException.class, () -> myHfqlExecutor.executeInitialSearch(input, null, mySrd)).getMessage());
	}

	@Test
	public void testError_NonGroupedSelectInCountClause() {
		initDao(Patient.class);

		String input = """
			from Patient
			select count(*), name.family
			""";

		assertEquals("Unable to select on non-grouped column in a count expression: name.family",
			assertThrows(InvalidRequestException.class, () -> myHfqlExecutor.executeInitialSearch(input, null, mySrd)).getMessage());
	}

	@SuppressWarnings("unchecked")
	private <T extends IBaseResource> IFhirResourceDao<T> initDao(Class<T> theType) {
		IFhirResourceDao<T> retVal = mock(IFhirResourceDao.class);
		String type = myCtx.getResourceType(theType);
		when(myDaoRegistry.getResourceDao(type)).thenReturn(retVal);
		return retVal;
	}

	@Nonnull
	private static Observation createCardiologyNoteObservation(String id, String noteText) {
		Observation obs = new Observation();
		obs.setId(id);
		obs.getCode().addCoding()
			.setSystem("http://loinc.org")
			.setCode("34752-6");
		obs.setValue(new StringType(noteText));
		return obs;
	}

	@Nonnull
	private static Observation createWeightObservationWithKilos(String obsId, long kg) {
		Observation obs = new Observation();
		obs.setId(obsId);
		obs.getCode().addCoding()
			.setSystem("http://loinc.org")
			.setCode("29463-7");
		obs.setValue(new Quantity(null, kg, "http://unitsofmeasure.org", "kg", "kg"));
		return obs;
	}

	@Nonnull
	private static Observation createWeightObservationWithPounds(String obsId, long thePounds) {
		Observation obs = new Observation();
		obs.setId(obsId);
		obs.getCode().addCoding()
			.setSystem("http://loinc.org")
			.setCode("29463-7");
		obs.setValue(new Quantity(null, thePounds, "http://unitsofmeasure.org", "[lb_av]", "[lb_av]"));
		return obs;
	}

	@Nonnull
	private static SimpleBundleProvider createProviderWithSparseNames() {
		Patient patientNoValues = new Patient();
		patientNoValues.setActive(true);
		Patient patientFamilyNameOnly = new Patient();
		patientFamilyNameOnly.addName().setFamily("Simpson");
		Patient patientGivenNameOnly = new Patient();
		patientGivenNameOnly.addName().addGiven("Homer");
		Patient patientBothNames = new Patient();
		patientBothNames.addName().setFamily("Simpson").addGiven("Homer");
		return new SimpleBundleProvider(List.of(
			patientNoValues, patientFamilyNameOnly, patientGivenNameOnly, patientBothNames));
	}

	@Nonnull
	private static SimpleBundleProvider createProviderWithSomeSimpsonsAndFlanders() {
		return new SimpleBundleProvider(List.of(
			createPatientHomerSimpson(),
			createPatientNedFlanders(),
			createPatientBartSimpson(),
			createPatientLisaSimpson(),
			createPatientMaggieSimpson()
		));
	}

	@Nonnull
	private static SimpleBundleProvider createProviderWithSomeSimpsonsAndFlandersWithSomeDuplicates() {
		return new SimpleBundleProvider(List.of(
			createPatientHomerSimpson(),
			createPatientHomerSimpson(),
			createPatientNedFlanders(),
			createPatientNedFlanders(),
			createPatientBartSimpson(),
			createPatientLisaSimpson(),
			createPatientMaggieSimpson()));
	}

	@Nonnull
	private static Patient createPatientMaggieSimpson() {
		Patient maggie = new Patient();
		maggie.addName().setFamily("Simpson").addGiven("Maggie").addGiven("Evelyn");
		maggie.addIdentifier().setSystem("http://system").setValue("value4");
		return maggie;
	}

	@Nonnull
	private static Patient createPatientLisaSimpson() {
		Patient lisa = new Patient();
		lisa.addName().setFamily("Simpson").addGiven("Lisa").addGiven("Marie");
		lisa.addIdentifier().setSystem("http://system").setValue("value3");
		return lisa;
	}

	@Nonnull
	private static Patient createPatientBartSimpson() {
		Patient bart = new Patient();
		bart.addName().setFamily("Simpson").addGiven("Bart").addGiven("El Barto");
		bart.addIdentifier().setSystem("http://system").setValue("value2");
		return bart;
	}

	@Nonnull
	private static Patient createPatientNedFlanders() {
		Patient nedFlanders = new Patient();
		nedFlanders.addName().setFamily("Flanders").addGiven("Ned");
		nedFlanders.addIdentifier().setSystem("http://system").setValue("value1");
		return nedFlanders;
	}

	@Nonnull
	private static Patient createPatientHomerSimpson() {
		Patient homer = new Patient();
		homer.addName().setFamily("Simpson").addGiven("Homer").addGiven("Jay");
		homer.addIdentifier().setSystem("http://system").setValue("value0");
		return homer;
	}

}
