package water.api;

import hex.Model;
import hex.ModelBuilder;
import hex.ModelParametersBuilderFactory;
import hex.grid.Grid;
import hex.grid.GridSearch;
import hex.grid.HyperSpaceSearchCriteria;
import hex.schemas.GridSearchSchema;
import water.H2O;
import water.Job;
import water.Key;
import water.TypeMap;
import water.exceptions.H2OIllegalArgumentException;
import water.util.PojoUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * A generic grid search handler implementing launch of grid search.
 *
 * <p>A model specific grid search handlers should inherit from class and implements corresponding
 * methods.
 *
 * FIXME: how to get rid of P, since it is already enforced by S
 *
 * @param <G>  Implementation output of grid search
 * @param <MP> Type of model parameters
 * @param <P>  Type of schema representing model parameters
 * @param <S>  Schema representing structure of grid search end-point
 */
public class GridSearchHandler<G extends Grid<MP>,
    S extends GridSearchSchema<G, S, MP, P>,
    MP extends Model.Parameters,
    P extends ModelParametersSchema> extends Handler {

  // Invoke the handler with parameters.  Can throw any exception the called handler can throw.
  // TODO: why does this do its own params filling?
  // TODO: why does this do its own sub-dispatch?
  @Override S handle(int version, water.api.Route route, Properties parms) throws Exception {
    // Only here for train or validate-parms
    if( !route._handler_method.getName().equals("train") )
      throw water.H2O.unimpl();

    // Peek out the desired algo from the URL
    String ss[] = route._url_pattern_raw.split("/");
    String algoURLName = ss[3]; // {}/{99}/{Grid}/{gbm}/
    String algoName = ModelBuilder.algoName(algoURLName); // gbm -> GBM; deeplearning -> DeepLearning
    String schemaDir = ModelBuilder.schemaDirectory(algoURLName);
    // Get the latest version of this algo: /99/Grid/gbm  ==> GBMV3
    String algoSchemaName = Schema.schemaClass(version, algoName).getSimpleName(); // GBMV3
    int algoVersion = Integer.valueOf(algoSchemaName.substring(algoSchemaName.lastIndexOf("V")+1)); // '3'

    // TODO: this is a horrible hack which is going to cause maintenance problems:
    String paramSchemaName = schemaDir+algoName+"V"+algoVersion+"$"+ModelBuilder.paramName(algoURLName)+"V"+algoVersion;

    // Build the Grid Search schema, and fill it from the parameters
    S gss = (S) new GridSearchSchema();
    gss.init_meta();
    gss.parameters = (P)TypeMap.newFreezable(paramSchemaName);
    gss.parameters.init_meta();

    // Get default parameters, then overlay the passed-in values
    ModelBuilder builder = ModelBuilder.make(algoURLName,null,null); // Default parameter settings
    gss.parameters.fillFromImpl(builder._parms); // Defaults for this builder into schema



    gss.fillFromParms(parms);   // Override defaults from user parms

    // Verify list of hyper parameters
    // Right now only names, no types
    validateHyperParams((P)gss.parameters, gss.hyper_parameters);

    // Get actual parameters
    MP params = (MP) gss.parameters.createAndFillImpl();

    // Get/create a grid for given frame
    // FIXME: Grid ID is not pass to grid search builder!
    Key<Grid> destKey = gss.grid_id != null ? gss.grid_id.key() : null;
    // Create target grid search object (keep it private for now)
    // Start grid search and return the schema back with job key
    Job<Grid> gsJob = GridSearch.startGridSearch(destKey,
                                                 params,
                                                 gss.hyper_parameters,
                                                 new DefaultModelParametersBuilderFactory<MP, P>(),
                                                 (HyperSpaceSearchCriteria)gss.search_criteria.createAndFillImpl());

    // Fill schema with job parameters
    // FIXME: right now we have to remove grid parameters which we sent back
    gss.hyper_parameters = null;
    gss.total_models = gsJob._result.get().getModelCount(); // TODO: looks like it's currently always 0
    gss.job = (JobV3) Schema.schema(version, Job.class).fillFromImpl(gsJob);

    return gss;
  }

  @SuppressWarnings("unused") // called through reflection by RequestServer
  public S train(int version, S gridSearchSchema) { throw H2O.fail(); }

  /**
   * Validate given hyper parameters with respect to type parameter P.
   *
   * It verifies that given parameters are annotated in P with @API annotation
   *
   * @param params      regular model build parameters
   * @param hyperParams map of hyper parameters
   */
  protected void validateHyperParams(P params, Map<String, Object[]> hyperParams) {
    List<SchemaMetadata.FieldMetadata> fsMeta = SchemaMetadata.getFieldMetadata(params);
    for (Map.Entry<String, Object[]> hparam : hyperParams.entrySet()) {
      SchemaMetadata.FieldMetadata fieldMetadata = null;
      // Found corresponding metadata about the field
      for (SchemaMetadata.FieldMetadata fm : fsMeta) {
        if (fm.name.equals(hparam.getKey())) {
          fieldMetadata = fm;
          break;
        }
      }
      if (fieldMetadata == null) {
        throw new H2OIllegalArgumentException(hparam.getKey(), "grid",
                                              "Unknown hyper parameter for grid search!");
      }
      if (!fieldMetadata.is_gridable) {
        throw new H2OIllegalArgumentException(hparam.getKey(), "grid",
                                              "Illegal hyper parameter for grid search! The parameter '"
                                              + fieldMetadata.name + " is not gridable!");
      }
    }
  }


  static class DefaultModelParametersBuilderFactory<MP extends Model.Parameters, PS extends ModelParametersSchema>
      implements ModelParametersBuilderFactory<MP> {

    @Override
    public ModelParametersBuilder<MP> get(MP initialParams) {
      return new ModelParametersFromSchemaBuilder<MP, PS>(initialParams);
    }

    @Override
    public PojoUtils.FieldNaming getFieldNamingStrategy() {
      return PojoUtils.FieldNaming.DEST_HAS_UNDERSCORES;
    }
  }

  /**
   * Model parameters factory building model parameters with respect to its schema. <p> A user calls
   * the {@link #set(String, Object)} method with names of parameters as they are defined in Schema.
   * The builder transfer the given values from Schema to corresponding model parameters object.
   * </p>
   *
   * @param <MP> type of model parameters
   * @param <PS> type of schema representing model parameters
   */
  public static class ModelParametersFromSchemaBuilder<MP extends Model.Parameters, PS extends ModelParametersSchema>
      implements ModelParametersBuilderFactory.ModelParametersBuilder<MP> {

    final private MP params;
    final private PS paramsSchema;
    final private ArrayList<String> fields;

    public ModelParametersFromSchemaBuilder(MP initialParams) {
      params = initialParams;
      paramsSchema = (PS) Schema.schema(Schema.getHighestSupportedVersion(), params.getClass());
      fields = new ArrayList<>(7);
    }

    public ModelParametersFromSchemaBuilder<MP, PS> set(String name, Object value) {
      try {
        Field f = paramsSchema.getClass().getField(name);
        API api = (API) f.getAnnotations()[0];
        Schema.setField(paramsSchema, f, name, value.toString(), api.required(),
                        paramsSchema.getClass());
        fields.add(name);
      } catch (NoSuchFieldException e) {
        throw new IllegalArgumentException("Cannot find field '" + name + "'" + " to value " + value, e);
      } catch (IllegalAccessException e) {
        throw new IllegalArgumentException("Cannot set field '" + name + "'" + " to value " + value, e);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Cannot set field '" + name + "'" + " to value" + value, e);
      }
      return this;
    }

    public MP build() {
      PojoUtils
          .copyProperties(params, paramsSchema, PojoUtils.FieldNaming.DEST_HAS_UNDERSCORES, null,
                          fields.toArray(new String[fields.size()]));
      // FIXME: handle these train/valid fields in different way
      // See: ModelParametersSchema#fillImpl
      if (params._valid == null && paramsSchema.validation_frame != null) {
        params._valid = Key.make(paramsSchema.validation_frame.name);
      }
      if (params._train == null && paramsSchema.training_frame != null) {
        params._train = Key.make(paramsSchema.training_frame.name);
      }

      return params;
    }
  }
}
