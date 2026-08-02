// Shared enums (mirror src/main/java/com/metabion/domain/)
export type Sex = 'FEMALE' | 'MALE' | 'INTERSEX' | 'PREFER_NOT_TO_SAY'
export type DietAdherenceLevel = 'FULL' | 'MOSTLY' | 'PARTIAL' | 'LOW' | 'NOT_FOLLOWED'
export type AppetiteLevel = 'LOW' | 'NORMAL' | 'HIGH' | 'VARIABLE'
export type MealType = 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK' | 'DRINK' | 'OTHER'
export type DietDeviationCategory = 'EXCESS_CARBS' | 'NON_PROTOCOL_FOOD' | 'MISSED_MEAL' | 'DINING_OUT' | 'ALCOHOL' | 'GI_TOLERANCE' | 'OTHER'
export type DietDeviationSeverity = 'MINOR' | 'MODERATE' | 'MAJOR'
export type MeasurementType = 'KETONE' | 'GLUCOSE'
export type MeasurementUnit = 'MMOL_L' | 'MG_DL'
export type MeasurementContext = 'FASTING' | 'PRE_MEAL' | 'POST_MEAL' | 'BEDTIME' | 'SYMPTOMS' | 'OTHER'
export type FlareState = 'NO_FLARE' | 'SUSPECTED_FLARE' | 'ACTIVE_FLARE'
export type SymptomAnswerType = 'SINGLE_CHOICE' | 'NUMERIC' | 'TEXT'
export type LabTestCategory = 'INFLAMMATION' | 'HEMATOLOGY' | 'NUTRITION' | 'ELECTROLYTE' | 'LIVER' | 'KIDNEY'
export type LabResultSource = 'MANUAL' | 'IMPORTED'
export type LabResultConfirmationStatus = 'CONFIRMED' | 'UNCONFIRMED'
export type IbdDiagnosisType = 'CROHNS_DISEASE' | 'ULCERATIVE_COLITIS' | 'IBD_UNCLASSIFIED'
export type DiseaseActivityEstimate = 'REMISSION' | 'MILD' | 'MODERATE' | 'SEVERE' | 'UNKNOWN'
export type SteroidUse = 'NONE' | 'CURRENT' | 'RECENT_LAST_3_MONTHS'
export type AdvancedTherapyExposure = 'NEVER_USED' | 'CURRENT' | 'PAST' | 'UNKNOWN'
export type OnboardingReviewStatus = 'PENDING_REVIEW' | 'REVIEWED' | 'NEEDS_FOLLOW_UP'
export type EducationLanguage = 'EN' | 'CS'
export type PatientAccessClientType = 'MCP_CLAUDE' | 'MCP_CODEX' | 'MCP_OTHER' | 'MOBILE_IOS' | 'MOBILE_ANDROID' | 'INTERNAL_TEST'

// Auth
export interface LoginResponse {
  status: 'AUTHENTICATED' | 'MFA_REQUIRED'
  email: string
  roles: string[]
  challengeId: string | null
  methods: string[] | null
}
export interface MeResponse { email: string; roles: string[] }
export interface CsrfTokenResponse { token: string; headerName: string }

// Account
export interface PatientProfile {
  dateOfBirth: string // yyyy-MM-dd
  sex: Sex
  countryRegion: string
  timezone: string
}

// Access tokens
export interface IssuePatientAccessTokenRequest {
  clientType: PatientAccessClientType
  displayLabel: string
  expiresInDays: number
  scopes: string[]
}
export interface IssuePatientAccessTokenResponse {
  tokenId: number
  plainToken: string
  clientType: PatientAccessClientType
  displayLabel: string
  expiresAt: string
  scopes: string[]
}
export interface PatientAccessTokenSummary {
  tokenId: number
  clientType: PatientAccessClientType
  displayLabel: string
  createdAt: string
  expiresAt: string
  lastUsedAt: string | null
  scopes: string[]
}

// Diet logs
export interface MealRequest { mealType: MealType; foodDescription?: string; notes?: string }
export interface DeviationRequest { mealIndex?: number | null; deviationCategory: DietDeviationCategory; severity: DietDeviationSeverity; notes?: string }
export interface PhotoUploadReferenceRequest { mealIndex?: number | null; uploadId: number; caption?: string }
export interface DailyMeasurementEntryRequest {
  measurementType: MeasurementType
  value: number
  unit: MeasurementUnit
  measuredAt: string
  context: MeasurementContext
  notes?: string
  metadata?: string
}
export interface DailyDietLogRequest {
  logDate: string
  adherenceLevel: DietAdherenceLevel
  appetiteLevel: AppetiteLevel
  notes?: string
  metadata?: string
  meals: MealRequest[]
  deviations: DeviationRequest[]
  photoReferences: PhotoUploadReferenceRequest[]
  measurements: DailyMeasurementEntryRequest[]
}
export interface MealResponse { id: number; mealType: MealType; foodDescription: string | null; notes: string | null; sortOrder: number }
export interface DeviationResponse { id: number; mealId: number | null; deviationCategory: DietDeviationCategory; severity: DietDeviationSeverity; notes: string | null; sortOrder: number }
export interface PhotoReferenceResponse { id: number; mealId: number | null; originalFilename: string; contentType: string; sizeBytes: number; caption: string | null; contentUrl: string; sortOrder: number }
export interface DailyMeasurementEntryResponse {
  id: number
  patientProfileId: number
  dailyDietLogId: number
  measurementType: MeasurementType
  value: number
  unit: MeasurementUnit
  measuredAt: string
  context: MeasurementContext
  notes: string | null
  metadata: string | null
  createdAt: string
}
export interface DailyDietLogResponse {
  id: number
  patientProfileId: number
  patientEmail: string
  logDate: string
  adherenceLevel: DietAdherenceLevel
  appetiteLevel: AppetiteLevel
  notes: string | null
  metadata: string | null
  createdAt: string
  updatedAt: string
  meals: MealResponse[]
  deviations: DeviationResponse[]
  photoReferences: PhotoReferenceResponse[]
  measurements: DailyMeasurementEntryResponse[]
}
export interface DailyDietLogSummary {
  id: number
  patientProfileId: number
  patientEmail: string
  logDate: string
  adherenceLevel: DietAdherenceLevel
  appetiteLevel: AppetiteLevel
  mealCount: number
  deviationCount: number
  measurementCount: number
  notesPreview: string | null
}
export interface DietLogPhotoUploadResponse {
  uploadId: number
  originalFilename: string
  contentType: string
  sizeBytes: number
  caption: string | null
  contentUrl: string
}

// Symptoms
export interface SymptomOption { id: number; stableKey: string; label: string; numericScore: number | null }
export interface SymptomQuestion {
  id: number
  stableKey: string
  label: string
  helpText: string | null
  answerType: SymptomAnswerType
  required: boolean
  minNumericValue: number | null
  maxNumericValue: number | null
  options: SymptomOption[]
}
export interface SymptomQuestionnaire {
  id: number
  stableKey: string
  displayName: string
  versionId: number
  versionNumber: number
  questions: SymptomQuestion[]
}
export interface AnswerRequest { questionId: number; optionId?: number | null; answerText?: string | null; answerNumeric?: number | null }
export interface SymptomCheckInRequest {
  checkInDate: string
  questionnaireVersionId: number
  flareState: FlareState
  answers: AnswerRequest[]
  notes?: string
}
export interface AnswerResponse {
  questionId: number
  questionStableKey: string
  label: string
  answerType: SymptomAnswerType
  optionId: number | null
  optionStableKey: string | null
  optionLabel: string | null
  answerText: string | null
  answerNumeric: number | null
  numericScore: number | null
}
export interface SymptomCheckInResponse {
  id: number
  patientProfileId: number
  questionnaireVersionId: number
  checkInDate: string
  flareState: FlareState
  totalSymptomScore: number | null
  notes: string | null
  answers: AnswerResponse[]
  createdAt: string
  updatedAt: string
}
export interface MeasurementPoint { id: number; measurementType: MeasurementType; value: number; unit: MeasurementUnit; measuredAt: string; context: MeasurementContext }
export interface DayTrend {
  date: string
  symptomCheckInId: number | null
  symptomScore: number | null
  flareState: FlareState | null
  dietLogId: number | null
  adherenceLevel: DietAdherenceLevel | null
  appetiteLevel: AppetiteLevel | null
  glucoseMeasurements: MeasurementPoint[]
  ketoneMeasurements: MeasurementPoint[]
}
export interface DailyTrendResponse {
  patientProfileId: number
  from: string
  to: string
  glucoseUnit: MeasurementUnit
  timezone: string
  days: DayTrend[]
}

// Labs
export interface LabTestDefinition { code: string; label: string; category: LabTestCategory; canonicalUnit: string; displayScale: number; allowedUnits: string[] }
export interface LabResultRequest { testCode: string; value: number; unit: string; referenceLower?: number | null; referenceUpper?: number | null }
export interface LabResultSetRequest {
  resultSetId?: number | null
  version?: number | null
  collectionDate: string
  notes?: string
  results: LabResultRequest[]
}
export interface LabResultResponse {
  id: number
  testCode: string
  label: string
  reportedValue: number
  reportedUnit: string
  canonicalValue: number
  canonicalUnit: string
  referenceLower: number | null
  referenceUpper: number | null
}
export interface LabResultSetResponse {
  id: number
  version: number
  patientProfileId: number
  collectionDate: string
  notes: string | null
  source: LabResultSource
  confirmationStatus: LabResultConfirmationStatus
  createdByCurrentPatient: boolean
  createdAt: string
  updatedAt: string
  results: LabResultResponse[]
}
export interface LabTrendPoint {
  resultSetId: number
  resultSetVersion: number
  collectionDate: string
  canonicalValue: number
  reportedValue: number
  reportedUnit: string
  referenceLower: number | null
  referenceUpper: number | null
  editable: boolean
}
export interface LabTrendResponse {
  patientProfileId: number
  testCode: string
  label: string
  canonicalUnit: string
  displayScale: number
  from: string
  to: string
  points: LabTrendPoint[]
}

// Onboarding
export interface OnboardingSubmissionRequest {
  onboardingContext?: string
  diagnosisType: IbdDiagnosisType
  diagnosisYear?: number | null
  diseaseLocation?: string
  diseaseBehavior?: string
  activityEstimate: DiseaseActivityEstimate
  currentMedications?: string
  steroidUse: SteroidUse
  advancedTherapyExposure: AdvancedTherapyExposure
  medicationNotes?: string
  labsCollectedAt?: string | null
  crpMgL?: number | null
  fecalCalprotectinUgG?: number | null
  hemoglobinGDl?: number | null
  albuminGDl?: number | null
  labNotes?: string
}
export interface OnboardingSubmissionResponse extends OnboardingSubmissionRequest {
  id: number
  patientProfileId: number
  patientEmail: string
  version: number
  createdAt: string
  submittedAt: string
  dateOfBirth: string | null
  sex: Sex | null
  countryRegion: string | null
  timezone: string | null
  reviewStatus: OnboardingReviewStatus
}
export interface OnboardingSubmissionSummary {
  id: number
  patientProfileId: number
  patientEmail: string
  onboardingContext: string | null
  version: number
  submittedAt: string
  diagnosisType: IbdDiagnosisType
  reviewStatus: OnboardingReviewStatus
}

// Education
export interface EducationModuleSummary {
  moduleSlug: string
  topic: string
  sortOrder: number
  version: number
  requestedLanguage: EducationLanguage
  contentLanguage: EducationLanguage
  title: string
  summary: string | null
  lessonCount: number
  completedLessonCount: number | null
  completed: boolean | null
  publishedAt: string | null
}
export interface EducationLesson {
  lessonSlug: string
  sortOrder: number
  requestedLanguage: EducationLanguage
  contentLanguage: EducationLanguage
  title: string
  summary: string | null
  bodyMarkdown: string | null
  bodyHtml: string | null
  completed: boolean | null
}
export interface EducationModuleDetail extends Omit<EducationModuleSummary, never> {
  lessons: EducationLesson[]
}

// Red flags (mirror src/main/java/com/metabion/dto/redflag/)
export type RedFlagSeverity = 'ROUTINE_REVIEW' | 'URGENT_REVIEW' | 'EMERGENCY'
export type RedFlagSourceType = 'SYMPTOM_CHECK_IN' | 'LAB_RESULT_SET'

export interface PatientRedFlagEvent {
  eventId: number
  ruleKey: string
  severity: RedFlagSeverity
  detectedAt: string // ISO instant
  sourceType: RedFlagSourceType
  sourceId: number
  current: boolean
  supersededAt: string | null // ISO instant
}

export interface PatientRedFlagSnapshot {
  highestSeverity: RedFlagSeverity | null
  flags: PatientRedFlagEvent[]
}

export interface PatientRedFlagHistoryPage {
  items: PatientRedFlagEvent[]
  nextCursor: string | null
}

export interface RedFlagHistoryParams {
  from?: string // yyyy-MM-dd, inclusive
  to?: string // yyyy-MM-dd, inclusive
  severity?: RedFlagSeverity
  cursor?: string
  size?: number
}
