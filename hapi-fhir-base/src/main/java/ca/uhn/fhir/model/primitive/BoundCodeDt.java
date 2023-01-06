package ca.uhn.fhir.model.primitive;

import ca.uhn.fhir.model.api.IValueSetEnumBinder;
import ca.uhn.fhir.model.api.annotation.DatatypeDef;
import org.apache.commons.lang3.Validate;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

@DatatypeDef(name = "code", isSpecialization = true)
public class BoundCodeDt<T extends Enum<?>> extends CodeDt {

	private IValueSetEnumBinder<T> myBinder;

	/**
	 * @deprecated This constructor is provided only for serialization support. Do not call it directly!
	 */
	@Deprecated
	public BoundCodeDt() {
		// nothing
	}

	public BoundCodeDt(IValueSetEnumBinder<T> theBinder) {
		Validate.notNull(theBinder, "theBinder must not be null");
		myBinder = theBinder;
	}

	public BoundCodeDt(IValueSetEnumBinder<T> theBinder, T theValue) {
		Validate.notNull(theBinder, "theBinder must not be null");
		myBinder = theBinder;
		setValueAsEnum(theValue);
	}

	public IValueSetEnumBinder<T> getBinder() {
		return myBinder;
	}
	
	public T getValueAsEnum() {
		Validate.notNull(myBinder, "This object does not have a binder. Constructor BoundCodeDt() should not be called!");
		T retVal = myBinder.fromCodeString(getValue());
		if (retVal == null) {
			// TODO: throw special exception type?
		}
		return retVal;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void readExternal(ObjectInput theIn) throws IOException, ClassNotFoundException {
		super.readExternal(theIn);
		myBinder = (IValueSetEnumBinder<T>) theIn.readObject();
	}

	public void setValueAsEnum(T theValue) {
		Validate.notNull(myBinder, "This object does not have a binder. Constructor BoundCodeDt() should not be called!");
		if (theValue==null) {
			setValue(null);
		} else {
			setValue(myBinder.toCodeString(theValue));
		}
	}

	@Override
	public void writeExternal(ObjectOutput theOut) throws IOException {
		super.writeExternal(theOut);
		theOut.writeObject(myBinder);
	}
}
